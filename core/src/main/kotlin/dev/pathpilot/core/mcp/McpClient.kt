package dev.pathpilot.core.mcp

import dev.pathpilot.core.network.OutboundHttpClientFactory
import dev.pathpilot.core.network.OutboundProxyConfig
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** A cancellation handle spans all HTTP pages belonging to one MCP request. */
class McpOperation {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var cancelCurrent: (() -> Unit)? = null
    fun cancel() { cancelled.set(true); cancelCurrent?.invoke() }
    internal fun attach(cancel: () -> Unit) {
        cancelCurrent = cancel
        if (cancelled.get()) cancel()
        checkActive()
    }
    internal fun detach() { cancelCurrent = null }
    internal fun checkActive() { if (cancelled.get()) throw McpException("MCP request cancelled", code = -32800) }
}

/** Blocking Streamable HTTP client for the 2025-11-25 protocol. Each instance owns one session. */
class McpClient(
    connection: McpConnection,
    private val proxyProvider: () -> OutboundProxyConfig = { OutboundProxyConfig() },
    httpClient: OkHttpClient = defaultHttpClient(),
) : Closeable {
    val connection = McpValidation.normalized(connection)
    private val transport = OutboundHttpClientFactory(httpClient)
    private val ids = AtomicLong()
    private val closed = AtomicBoolean(false)
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    @Volatile private var sessionId: String? = null
    @Volatile private var info: McpServerInfo? = null
    @Volatile private var toolsAvailable = false

    @Synchronized
    fun connect(): McpServerInfo {
        info?.let { checkOpen(); return it }
        checkOpen()
        val response = rpc("initialize", buildJsonObject {
            put("protocolVersion", MCP_PROTOCOL_VERSION)
            put("capabilities", buildJsonObject {})
            put("clientInfo", buildJsonObject { put("name", "PathPilot"); put("version", "1") })
        }, initializing = true)
        val version = (response["protocolVersion"] as? JsonPrimitive)?.contentOrNull
            ?: throw McpException("MCP initialize response has no protocolVersion")
        if (version != MCP_PROTOCOL_VERSION) throw McpException("Unsupported MCP protocol version: $version")
        val server = response["serverInfo"] as? JsonObject ?: throw McpException("MCP initialize response has no serverInfo")
        val name = (server["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw McpException("MCP server has no name")
        if ((server["version"] as? JsonPrimitive)?.isString != true) throw McpException("MCP server has no version")
        val capabilities = response["capabilities"] as? JsonObject ?: throw McpException("MCP server has no capabilities")
        toolsAvailable = capabilities["tools"] is JsonObject
        notify(buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/initialized") })
        return McpServerInfo(name, version).also { info = it }
    }

    fun listTools(operation: McpOperation = McpOperation()): List<McpTool> {
        connect()
        if (!toolsAvailable) throw McpException("MCP server does not advertise tools", code = -32601)
        val tools = mutableListOf<McpTool>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            operation.checkActive()
            val result = rpc("tools/list", buildJsonObject { cursor?.let { put("cursor", it) } }, operation)
            val page = result["tools"] as? JsonArray ?: throw McpException("MCP tools/list response has no tools array")
            page.forEach { element ->
                val definition = element as? JsonObject ?: throw McpException("Invalid MCP tool definition")
                val name = definition["name"] as? JsonPrimitive
                if (name?.isString != true || name.content.isBlank() || definition["inputSchema"] !is JsonObject)
                    throw McpException("MCP tool requires a name and inputSchema")
                tools += McpTool(definition)
            }
            cursor = when (val next = result["nextCursor"]) {
                null, JsonNull -> null
                is JsonPrimitive -> next.takeIf { it.isString }?.content
                    ?: throw McpException("Invalid MCP pagination cursor")
                else -> throw McpException("Invalid MCP pagination cursor")
            }
            if (cursor != null && !cursors.add(cursor)) throw McpException("MCP pagination repeated a cursor")
        } while (cursor != null)
        return tools
    }

    fun callTool(name: String, arguments: JsonObject = buildJsonObject {}, operation: McpOperation = McpOperation()): JsonObject {
        connect()
        return rpc("tools/call", buildJsonObject { put("name", name); put("arguments", arguments) }, operation)
    }

    fun cancel() { calls.forEach(Call::cancel) }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancel()
        sessionId?.let {
            // DELETE is best-effort session cleanup; its callback never delays Android service stop.
            val request = requestBuilder().delete().build()
            transport.client(proxyProvider()).newBuilder().callTimeout(3, TimeUnit.SECONDS).build()
                .newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) = Unit
                    override fun onResponse(call: Call, response: Response) { response.close() }
                })
        }
    }

    private fun rpc(method: String, params: JsonObject, operation: McpOperation = McpOperation(), initializing: Boolean = false): JsonObject {
        checkOpen(); operation.checkActive()
        val id = JsonPrimitive(ids.incrementAndGet())
        val payload = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) }
        val call = transport.client(proxyProvider()).newCall(requestBuilder(initializing).post(payload.toString().toRequestBody(JSON)).build())
        calls.add(call)
        try {
            checkOpen()
            operation.attach {
                call.cancel()
                sendCancellation(id)
            }
            call.execute().use { response ->
                if (!response.isSuccessful) throw McpException("MCP returned HTTP ${response.code}", httpStatus = response.code)
                if (initializing) {
                    response.header("Mcp-Session-Id")?.let { value ->
                        if (value.isEmpty() || value.any { it < '!' || it > '~' }) throw McpException("Invalid MCP session ID")
                        sessionId = value
                    }
                }
                val message = readResponse(response, id)
                val error = message["error"] as? JsonObject
                if (error != null) throw McpException(
                    (error["message"] as? JsonPrimitive)?.contentOrNull ?: "MCP request failed",
                    code = (error["code"] as? JsonPrimitive)?.intOrNull,
                    data = error["data"],
                )
                return message["result"] as? JsonObject ?: throw McpException("MCP response has no result")
            }
        } catch (error: IOException) {
            if (call.isCanceled()) throw McpException("MCP request cancelled", code = -32800, cause = error)
            throw error
        } finally {
            calls.remove(call)
            operation.detach()
        }
    }

    private fun readResponse(response: Response, id: JsonPrimitive): JsonObject {
        val body = response.body ?: throw McpException("MCP response has no body")
        val type = body.contentType()?.let { "${it.type}/${it.subtype}" }
        if (type == "application/json") {
            val source = body.source()
            if (source.request(MAX_MESSAGE_BYTES + 1)) throw McpException("MCP response exceeds 8 MiB")
            return parseMessage(source.readUtf8()).also { validateResponse(it, id) }
        }
        if (type != "text/event-stream") throw McpException("MCP response must be JSON or SSE")
        val source = body.source()
        val data = StringBuilder()
        while (!source.exhausted()) {
            val line = try { source.readUtf8LineStrict(MAX_MESSAGE_BYTES) }
                catch (error: java.io.EOFException) {
                    if (source.buffer.size > MAX_MESSAGE_BYTES) throw McpException("MCP SSE line exceeds 8 MiB", cause = error)
                    break
                }
            if (line.isEmpty()) {
                if (data.isNotEmpty()) {
                    val message = parseMessage(data.toString())
                    data.setLength(0)
                    if (message["id"] == id && message["method"] == null) {
                        validateResponse(message, id); return message
                    }
                    if (message["id"] != null && message["method"] != null) answerServerRequest(message)
                }
            } else if (line.startsWith("data:")) {
                val value = line.substring(5).removePrefix(" ")
                if (data.length + value.length > MAX_MESSAGE_BYTES) throw McpException("MCP SSE event exceeds 8 MiB")
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
        }
        throw McpException("MCP stream ended before its response")
    }

    private fun validateResponse(message: JsonObject, id: JsonPrimitive) {
        if (message["jsonrpc"] != JsonPrimitive("2.0") || message["id"] != id || message["method"] != null)
            throw McpException("MCP response ID or JSON-RPC version does not match")
        if ((message["result"] == null) == (message["error"] == null)) throw McpException("Invalid MCP JSON-RPC response")
    }

    private fun parseMessage(text: String): JsonObject = try {
        Json.parseToJsonElement(text) as? JsonObject ?: throw McpException("MCP response must be a JSON object")
    } catch (error: SerializationException) { throw McpException("Invalid MCP JSON", cause = error) }

    private fun answerServerRequest(message: JsonObject) {
        notify(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", message.getValue("id"))
            if (message["method"] == JsonPrimitive("ping")) put("result", buildJsonObject {})
            else put("error", buildJsonObject { put("code", -32601); put("message", "Client method not supported") })
        })
    }

    private fun notify(payload: JsonObject) {
        checkOpen()
        val call = transport.client(proxyProvider()).newCall(requestBuilder().post(payload.toString().toRequestBody(JSON)).build())
        calls.add(call)
        try {
            checkOpen()
            call.execute().use { response ->
                if (!response.isSuccessful) throw McpException("MCP returned HTTP ${response.code}", httpStatus = response.code)
            }
        } finally { calls.remove(call) }
    }

    private fun sendCancellation(id: JsonPrimitive) {
        if (closed.get()) return
        val payload = buildJsonObject {
            put("jsonrpc", "2.0"); put("method", "notifications/cancelled")
            put("params", buildJsonObject { put("requestId", id) })
        }
        transport.client(proxyProvider()).newBuilder().callTimeout(3, TimeUnit.SECONDS).build()
            .newCall(requestBuilder().post(payload.toString().toRequestBody(JSON)).build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = Unit
                override fun onResponse(call: Call, response: Response) { response.close() }
            })
    }

    private fun requestBuilder(initializing: Boolean = false): Request.Builder = Request.Builder().url(connection.url)
        .header("Accept", "application/json, text/event-stream")
        .apply {
            if (!initializing) header("MCP-Protocol-Version", MCP_PROTOCOL_VERSION)
            if (connection.bearerToken.isNotEmpty()) header("Authorization", "Bearer ${connection.bearerToken}")
            sessionId?.let { header("Mcp-Session-Id", it) }
        }

    private fun checkOpen() { if (closed.get()) throw McpException("MCP connection is closed", code = -32800) }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val MAX_MESSAGE_BYTES = 8L * 1024 * 1024
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS).build()
    }
}
