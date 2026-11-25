package dev.pathpilot.core.mcp

import dev.pathpilot.core.AccessKeys
import dev.pathpilot.core.network.OutboundProxyConfig
import dev.pathpilot.core.network.OutboundProxyMode
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class McpTransportTest {
    private lateinit var remote: MockWebServer
    private lateinit var fixture: FixtureDispatcher
    private val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()
    private val clients = mutableListOf<McpClient>()
    private var gateway: McpGatewayServer? = null
    @Volatile private lateinit var settings: McpSettings
    private val events = ConcurrentLinkedQueue<McpCallEvent>()
    private val executor = Executors.newFixedThreadPool(2)

    @Before fun start() {
        fixture = FixtureDispatcher()
        remote = MockWebServer().also { it.dispatcher = fixture; it.start() }
    }
    @After fun stop() {
        clients.forEach(McpClient::close)
        gateway?.stop()
        executor.shutdownNow()
        remote.shutdown()
    }

    private fun direct(proxy: () -> OutboundProxyConfig = { OutboundProxyConfig() }, url: String = remote.url("/mcp").toString()) =
        McpClient(McpConnection(name = "Remote", url = url, bearerToken = "upstream-secret"), proxy, http).also(clients::add)

    private fun startGateway(allowed: Set<String>? = null): McpGatewayServer {
        settings = McpSettings(port = 0, accessKey = AccessKeys.generate(), connections = listOf(
            McpConnection(id = "remote", name = "Remote tools", url = remote.url("/mcp").toString(),
                bearerToken = "upstream-secret", allowedTools = allowed),
        ))
        return McpGatewayServer({ settings }, onCall = { events += it }, httpClient = http)
            .also { it.start(5_000, true); gateway = it }
    }
    private fun throughGateway() = McpClient(McpConnection(name = "Phone", url = endpoint(), bearerToken = settings.accessKey), httpClient = http)
        .also(clients::add)
    private fun endpoint() = "http://127.0.0.1:${requireNotNull(gateway).listeningPort}/mcp/remote"

    @Test fun clientNegotiatesSessionAndAggregatesJsonAndSseToolPages() {
        val client = direct()
        assertEquals(McpServerInfo("Fixture MCP", MCP_PROTOCOL_VERSION), client.connect())
        val tools = client.listTools()
        assertEquals(listOf("echo", "hidden"), tools.map { it.name })
        assertEquals(true, tools.first().definition["annotations"]!!.jsonObject["readOnlyHint"]!!.jsonPrimitive.boolean)
        val output = client.callTool("echo", buildJsonObject { put("text", "hello") })
        assertEquals("hello", output["structuredContent"]!!.jsonObject["echo"]!!.jsonPrimitive.content)
        assertEquals("upstream-1", output["structuredContent"]!!.jsonObject["session"]!!.jsonPrimitive.content)
        val requests = fixture.seen.toList()
        assertEquals("initialize", requests[0].method)
        assertNull(requests[0].session)
        assertEquals("notifications/initialized", requests[1].method)
        assertTrue(requests.drop(1).all { it.session == "upstream-1" && it.version == MCP_PROTOCOL_VERSION })
        assertTrue(requests.all { it.authorization == "Bearer upstream-secret" })
        client.close()
        assertTrue(fixture.deleted.await(2, TimeUnit.SECONDS))
    }

    @Test fun clientUsesConfiguredHttpProxyWithoutReplacingSourceCredentials() {
        val client = direct(
            proxy = { OutboundProxyConfig(OutboundProxyMode.HTTP, "127.0.0.1", remote.port) },
            url = "http://remote.example/mcp",
        )
        client.connect()
        assertTrue(fixture.requestLines.first().startsWith("POST http://remote.example/mcp"))
        assertEquals("Bearer upstream-secret", fixture.seen.first().authorization)
    }

    @Test fun rpcErrorsPreserveTheirCodeAndDataAndMismatchedResponsesFail() {
        fixture.rpcFailure = true
        val error = assertThrows(McpException::class.java) { direct().callTool("echo") }
        assertEquals(-32042, error.code)
        assertEquals("quota", error.data!!.jsonObject["kind"]!!.jsonPrimitive.content)
        fixture.rpcFailure = false
        fixture.wrongId = true
        val invalid = assertThrows(McpException::class.java) { direct().connect() }
        assertTrue(invalid.message!!.contains("ID"))
    }

    @Test fun protocolVersionAndRepeatedPaginationCursorAreReported() {
        fixture.version = "2026-07-28"
        assertTrue(assertThrows(McpException::class.java) { direct().connect() }.message!!.contains("2026-07-28"))
        fixture.version = MCP_PROTOCOL_VERSION
        fixture.repeatCursor = true
        assertTrue(assertThrows(McpException::class.java) { direct().listTools() }.message!!.contains("cursor"))
    }

    @Test fun gatewayRequiresItsOwnKeyAndValidOriginBeforeConnectingUpstream() {
        startGateway()
        fun request(key: String?, origin: String? = null, host: String? = null) = Request.Builder().url(endpoint())
            .apply { key?.let { header("Authorization", "Bearer $it") }; origin?.let { header("Origin", it) }; host?.let { header("Host", it) } }
            .get().build()
        http.newCall(request(null)).execute().use { assertEquals(401, it.code) }
        http.newCall(request("upstream-secret")).execute().use { assertEquals(401, it.code) }
        http.newCall(request(settings.accessKey, "http://evil.example")).execute().use { assertEquals(403, it.code) }
        http.newCall(request(settings.accessKey, "http://evil.example:${gateway!!.listeningPort}", "evil.example:${gateway!!.listeningPort}"))
            .execute().use { assertEquals(403, it.code) }
        http.newCall(request(settings.accessKey, "http://127.0.0.1:${gateway!!.listeningPort}")).execute().use { assertEquals(405, it.code) }
        assertEquals(0, remote.requestCount)
    }

    @Test fun gatewayFiltersListsAndCallsUsingCurrentConnectionSettings() {
        startGateway(allowed = setOf("echo"))
        val client = throughGateway()
        assertEquals(listOf("echo"), client.listTools().map { it.name })
        assertEquals("hello", client.callTool("echo", buildJsonObject { put("text", "hello") })["structuredContent"]!!.jsonObject["echo"]!!.jsonPrimitive.content)
        assertEquals(-32602, assertThrows(McpException::class.java) { client.callTool("hidden") }.code)
        assertEquals(1, fixture.seen.count { it.method == "tools/call" })
        settings = settings.copy(connections = settings.connections.map { it.copy(allowedTools = emptySet()) })
        assertEquals(emptyList<McpTool>(), client.listTools())
        assertEquals(-32602, assertThrows(McpException::class.java) { client.callTool("echo") }.code)
        settings = settings.copy(connections = settings.connections.map { it.copy(enabled = false) })
        assertEquals(404, assertThrows(McpException::class.java) { client.listTools() }.httpStatus)
        assertTrue(fixture.deleted.await(2, TimeUnit.SECONDS))
        assertTrue(fixture.seen.all { it.authorization == "Bearer upstream-secret" })
        assertEquals(3, events.size)
        assertTrue(events.first().success)
        assertTrue(events.drop(1).all { !it.success && it.errorCode == "tool_disabled" })
    }

    @Test fun downstreamSessionsKeepTheirUpstreamSessionsSeparateAndDeleteCleansUp() {
        startGateway()
        val first = throughGateway(); val second = throughGateway()
        first.connect(); second.connect()
        val firstOutput = first.callTool("echo")["structuredContent"]!!.jsonObject["session"]
        val secondOutput = second.callTool("echo")["structuredContent"]!!.jsonObject["session"]
        assertEquals(JsonPrimitive("upstream-1"), firstOutput)
        assertEquals(JsonPrimitive("upstream-2"), secondOutput)
        first.close()
        assertTrue(fixture.deleted.await(2, TimeUnit.SECONDS))
        assertEquals(secondOutput, second.callTool("echo")["structuredContent"]!!.jsonObject["session"])
    }

    @Test fun upstreamExpiredSessionIsNotRetriedAndKeyRotationInvalidatesOldSessions() {
        startGateway()
        val client = throughGateway()
        client.connect()
        fixture.expireOnCall = true
        assertEquals(404, assertThrows(McpException::class.java) { client.callTool("echo") }.httpStatus)
        assertEquals(1, fixture.seen.count { it.method == "tools/call" })
        fixture.expireOnCall = false
        val next = throughGateway(); next.connect()
        settings = settings.copy(accessKey = AccessKeys.generate())
        assertEquals(401, assertThrows(McpException::class.java) { next.listTools() }.httpStatus)
    }

    @Test fun cancellingARequestInterruptsBothGatewayAndRemoteCall() {
        startGateway()
        val client = throughGateway(); client.connect()
        fixture.hangCall = true
        val operation = McpOperation()
        val pending = executor.submit<McpException> {
            assertThrows(McpException::class.java) { client.callTool("echo", operation = operation) }
        }
        assertTrue(fixture.callStarted.await(2, TimeUnit.SECONDS))
        operation.cancel()
        assertEquals(-32800, pending.get(2, TimeUnit.SECONDS).code)
        assertTrue(fixture.cancelled.await(3, TimeUnit.SECONDS))
    }

    @Test fun stoppingGatewayCancelsTheActiveRemoteRequest() {
        val server = startGateway()
        val client = throughGateway(); client.connect()
        fixture.hangCall = true
        val pending = executor.submit<Boolean> {
            try { client.callTool("echo"); false } catch (_: java.io.IOException) { true }
        }
        assertTrue(fixture.callStarted.await(2, TimeUnit.SECONDS))
        server.stop()
        assertTrue(pending.get(3, TimeUnit.SECONDS))
        assertTrue(fixture.deleted.await(3, TimeUnit.SECONDS))
    }

    @Test fun toolReportedFailureIsAValidResultAndSeparateFromTransportFailure() {
        startGateway()
        fixture.isToolError = true
        val output = throughGateway().callTool("echo")
        assertEquals(true, output["isError"]!!.jsonPrimitive.boolean)
        assertFalse(events.single().success)
        assertEquals("tool_error", events.single().errorCode)
    }

    @Test fun connectionValidationKeepsCredentialsOutOfToString() {
        val connection = McpConnection(name = " Tools ", url = remote.url("/mcp?token=url-secret").toString(), bearerToken = " bearer-secret ")
        val normalized = McpValidation.normalized(connection)
        assertEquals("Tools", normalized.name)
        assertEquals("bearer-secret", normalized.bearerToken)
        assertFalse(normalized.toString().contains("secret"))
        assertFalse(McpSettings(connections = listOf(normalized)).toString().contains("secret"))
        assertThrows(IllegalArgumentException::class.java) { McpValidation.normalized(connection.copy(url = "file:///tmp/tool")) }
        assertThrows(IllegalArgumentException::class.java) { McpValidation.normalized(connection.copy(url = "https://user:password@example.com/mcp")) }
        assertThrows(IllegalArgumentException::class.java) { McpValidation.normalized(connection.copy(bearerToken = "token\r\nInjected: true")) }
    }

    private data class Seen(val method: String, val session: String?, val authorization: String?, val version: String?)

    private class FixtureDispatcher : Dispatcher() {
        val seen = ConcurrentLinkedQueue<Seen>()
        val requestLines = ConcurrentLinkedQueue<String>()
        val deleted = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val callStarted = CountDownLatch(1)
        private val sessions = AtomicInteger()
        @Volatile var version = MCP_PROTOCOL_VERSION
        @Volatile var repeatCursor = false
        @Volatile var wrongId = false
        @Volatile var rpcFailure = false
        @Volatile var isToolError = false
        @Volatile var expireOnCall = false
        @Volatile var hangCall = false
        override fun dispatch(request: RecordedRequest): MockResponse {
            requestLines += request.requestLine
            if (request.method == "DELETE") { deleted.countDown(); return MockResponse().setResponseCode(204) }
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            val method = body["method"]?.jsonPrimitive?.content ?: "response"
            seen += Seen(method, request.getHeader("Mcp-Session-Id"), request.getHeader("Authorization"), request.getHeader("MCP-Protocol-Version"))
            if (method == "notifications/cancelled") { cancelled.countDown(); return MockResponse().setResponseCode(202) }
            if (body["id"] == null || method == "response") return MockResponse().setResponseCode(202)
            val id = if (wrongId) JsonPrimitive("wrong") else body.getValue("id")
            fun reply(result: JsonObject, sse: Boolean = false): MockResponse {
                val text = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }.toString()
                return MockResponse().setHeader("Content-Type", if (sse) "text/event-stream" else "application/json")
                    .setBody(if (sse) ": keepalive\n\ndata: $text\n\n" else text)
            }
            return when (method) {
                "initialize" -> reply(buildJsonObject {
                    put("protocolVersion", version); put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
                    put("serverInfo", buildJsonObject { put("name", "Fixture MCP"); put("version", "1") })
                }).setHeader("Mcp-Session-Id", "upstream-${sessions.incrementAndGet()}")
                "tools/list" -> {
                    val cursor = body["params"]!!.jsonObject["cursor"]
                    reply(buildJsonObject {
                        put("tools", buildJsonArray { add(buildJsonObject {
                            put("name", if (cursor == null) "echo" else "hidden")
                            put("description", "An offline fixture tool")
                            put("inputSchema", buildJsonObject { put("type", "object"); put("additionalProperties", false) })
                            put("annotations", buildJsonObject { put("readOnlyHint", true) })
                        }) })
                        if (cursor == null || repeatCursor) put("nextCursor", "next-page")
                    }, sse = cursor != null)
                }
                "tools/call" -> {
                    callStarted.countDown()
                    when {
                        hangCall -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        expireOnCall -> MockResponse().setResponseCode(404)
                        rpcFailure -> MockResponse().setHeader("Content-Type", "application/json").setBody(buildJsonObject {
                            put("jsonrpc", "2.0"); put("id", id)
                            put("error", buildJsonObject { put("code", -32042); put("message", "Tool quota reached"); put("data", buildJsonObject { put("kind", "quota") }) })
                        }.toString())
                        else -> reply(buildJsonObject {
                            put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "fixture") }) })
                            put("isError", isToolError)
                            put("structuredContent", buildJsonObject {
                                put("echo", body["params"]!!.jsonObject["arguments"]?.jsonObject?.get("text") ?: JsonPrimitive(""))
                                put("session", request.getHeader("Mcp-Session-Id"))
                            })
                        }, sse = true)
                    }
                }
                else -> MockResponse().setResponseCode(400)
            }
        }
    }
}
