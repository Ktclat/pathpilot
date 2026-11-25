package dev.pathpilot.app.ui.settings

import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/** A local Streamable HTTP upstream, with actual HTTP sessions and a fixture-only Bearer token. */
internal class OfflineMcpFixture : Closeable {
    val token = "offline-mcp-upstream-token"
    private val sequence = AtomicInteger()
    private val sessions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val methods = CopyOnWriteArrayList<String>()
    val calls = CopyOnWriteArrayList<String>()
    val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Authorization") != "Bearer $token")
                    return MockResponse().setResponseCode(401)
                if (request.method == "DELETE") {
                    val id = request.getHeader("Mcp-Session-Id") ?: return MockResponse().setResponseCode(400)
                    return MockResponse().setResponseCode(if (sessions.remove(id)) 204 else 404)
                }
                if (request.method != "POST") return MockResponse().setResponseCode(405)
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val method = body["method"]?.jsonPrimitive?.contentOrNull.orEmpty()
                methods += method
                if (method == "initialize") {
                    val id = "offline-session-${sequence.incrementAndGet()}"
                    sessions += id
                    return response(body, buildJsonObject {
                        put("protocolVersion", "2025-11-25")
                        put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
                        put("serverInfo", buildJsonObject { put("name", "Offline tools"); put("version", "1.0") })
                    }).addHeader("Mcp-Session-Id", id)
                }
                if (request.getHeader("Mcp-Session-Id") !in sessions) return MockResponse().setResponseCode(404)
                if (method == "notifications/initialized") return MockResponse().setResponseCode(202)
                return when (method) {
                    "tools/list" -> response(body, buildJsonObject {
                        put("tools", buildJsonArray {
                            add(tool("offline_echo", "回传测试文本 / Echo test text"))
                            add(tool("offline_status", "返回测试状态 / Return fixture status"))
                        })
                    })
                    "tools/call" -> {
                        val name = body["params"]!!.jsonObject["name"]!!.jsonPrimitive.content
                        calls += name
                        response(body, buildJsonObject {
                            put("content", buildJsonArray { add(buildJsonObject {
                                put("type", "text"); put("text", "offline result: $name")
                            }) })
                            put("isError", false)
                        })
                    }
                    "ping" -> response(body, buildJsonObject {})
                    else -> MockResponse().setResponseCode(400)
                }
            }
        }
        start()
    }
    val url: String get() = server.url("/mcp").toString()

    override fun close() { server.shutdown() }

    private fun tool(name: String, description: String) = buildJsonObject {
        put("name", name); put("description", description)
        put("inputSchema", buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("text", buildJsonObject { put("type", "string") }) })
        })
        put("annotations", buildJsonObject { put("readOnlyHint", true) })
    }

    private fun response(request: JsonObject, result: JsonObject) = MockResponse()
        .addHeader("Content-Type", "application/json")
        .setBody(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", request["id"] ?: JsonNull)
            put("result", result)
        }.toString())
}
