package dev.pathpilot.core.mcp

import dev.pathpilot.core.AccessKeys
import dev.pathpilot.core.network.OutboundProxyConfig
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.NetworkInterface
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Independent Tools-only gateway. Every downstream session owns a separate upstream client. */
class McpGatewayServer(
    private val settingsProvider: () -> McpSettings,
    private val proxyProvider: () -> OutboundProxyConfig = { OutboundProxyConfig() },
    private val onCall: (McpCallEvent) -> Unit = {},
    private val httpClient: OkHttpClient = McpClient.defaultHttpClient(),
) : NanoHTTPD("0.0.0.0", settingsProvider().port) {
    private class Session(val connection: McpConnection, val client: McpClient, val accessKey: String) {
        val requests = ConcurrentHashMap<String, McpOperation>()
        @Volatile var ready = false
        @Volatile var lastAccess = System.nanoTime()
        fun close() { requests.values.forEach(McpOperation::cancel); client.close() }
    }
    private val sessions = ConcurrentHashMap<String, Session>()
    private val initializing = ConcurrentHashMap.newKeySet<McpClient>()
    private val stopped = AtomicBoolean(false)
    private val lifecycle = Any()
    private val requestSlots = Semaphore(8)
    private val sessionSlots = Semaphore(32)

    override fun serve(http: IHTTPSession): Response {
        if (stopped.get()) return reject(503, "MCP sharing is stopped")
        if (!validOrigin(http.headers["origin"])) return reject(403, "Origin is not allowed")
        val settings = settingsProvider()
        val bearer = http.headers["authorization"]?.takeIf { it.startsWith("Bearer ", true) }?.substring(7).orEmpty()
        if (!AccessKeys.matches(settings.accessKey, bearer)) return reject(401, "A valid MCP Bearer key is required")
        val connectionId = http.uri.removePrefix("/mcp/")
        val connection = settings.connections.firstOrNull { it.id == connectionId && it.enabled }
        if (!http.uri.startsWith("/mcp/") || connection == null) {
            http.headers["mcp-session-id"]?.let { id ->
                sessions[id]?.takeIf { it.connection.id == connectionId }?.let { removeSession(id, it) }
            }
            return reject(404, "MCP connection is unavailable")
        }
        if (http.method == Method.GET) return reject(405, "GET notification streams are not supported").also { it.addHeader("Allow", "POST, DELETE") }
        if (http.method != Method.POST && http.method != Method.DELETE) return reject(405, "Use POST or DELETE").also { it.addHeader("Allow", "POST, DELETE") }
        val protocol = http.headers["mcp-protocol-version"]
        if (protocol != null && protocol != MCP_PROTOCOL_VERSION) return reject(400, "Unsupported MCP protocol version")
        expireSessions()
        val sessionId = http.headers["mcp-session-id"]
        val session = sessionId?.let(sessions::get)?.takeIf { it.connection.id == connectionId }
        if (sessionId != null && session == null) return reject(404, "MCP session not found")
        if (session != null && (session.accessKey != settings.accessKey || session.connection.url != connection.url ||
                session.connection.bearerToken != connection.bearerToken)) {
            removeSession(sessionId!!, session)
            return reject(404, "MCP connection changed; initialize a new session")
        }
        if (http.method == Method.DELETE) {
            if (session == null) return reject(400, "Mcp-Session-Id is required")
            removeSession(sessionId!!, session)
            return newFixedLengthResponse(Response.Status.NO_CONTENT, null, "")
        }
        if (http.headers["transfer-encoding"]?.isNotBlank() == true) return reject(411, "Content-Length is required")
        val length = http.headers["content-length"]?.toLongOrNull() ?: return reject(411, "Content-Length is required")
        if (length !in 1..MAX_REQUEST_BYTES) return reject(if (length > MAX_REQUEST_BYTES) 413 else 400, "MCP request must be between 1 byte and 2 MiB")
        if (http.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() != "application/json") return reject(415, "Content-Type must be application/json")
        if (http.headers["content-encoding"]?.let { it != "identity" } == true) return reject(415, "Compressed requests are not supported")
        val message = try {
            val bytes = ByteArray(length.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val read = http.inputStream.read(bytes, offset, bytes.size - offset)
                if (read < 0) throw IOException("Incomplete body")
                offset += read
            }
            Json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)) as? JsonObject
                ?: return rpcError(JsonNull, -32600, "JSON-RPC object required", 400)
        } catch (_: SerializationException) { return rpcError(JsonNull, -32700, "Invalid JSON", 400) }
        catch (_: IOException) { return reject(400, "Incomplete request body") }
        val id = message["id"]
        val method = (message["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (message["jsonrpc"] != JsonPrimitive("2.0") || method == null ||
            id != null && (id !is JsonPrimitive || id == JsonNull || !id.isString && id.doubleOrNull?.isFinite() != true))
            return rpcError(id ?: JsonNull, -32600, "Invalid JSON-RPC request", 400)
        val params = message["params"] as? JsonObject ?: buildJsonObject {}
        if (method == "initialize") {
            if (id == null || sessionId != null) return rpcError(id ?: JsonNull, -32600, "Initialize requires an ID and no existing session", 400)
            return initialize(connection, settings.accessKey, id, params)
        }
        if (session == null) return reject(400, "Initialize an MCP session first")
        session.lastAccess = System.nanoTime()
        if (id == null) {
            when (method) {
                "notifications/initialized" -> session.ready = true
                "notifications/cancelled" -> params["requestId"]?.let { session.requests[it.toString()]?.cancel() }
            }
            return newFixedLengthResponse(Response.Status.ACCEPTED, null, "")
        }
        if (method == "ping") return result(id, buildJsonObject {})
        if (!session.ready) return rpcError(id, -32000, "Send notifications/initialized first")
        if (method !in setOf("tools/list", "tools/call")) return rpcError(id, -32601, "Method not supported")
        if (!requestSlots.tryAcquire()) return rpcError(id, -32000, "MCP gateway is handling 8 requests; retry when one finishes", 429)
        val operation = McpOperation()
        if (session.requests.putIfAbsent(id.toString(), operation) != null) {
            requestSlots.release()
            return rpcError(id, -32600, "Request ID is already active", 400)
        }
        val started = System.nanoTime()
        val startedAt = System.currentTimeMillis()
        var toolName = ""
        var success = false
        var errorCode: String? = null
        try {
            if (stopped.get()) throw McpException("MCP sharing is stopped", code = -32800)
            if (method == "tools/list") {
                if (params["cursor"] != null) return rpcError(id, -32602, "This gateway returns the complete tool list; omit cursor")
                val tools = session.client.listTools(operation)
                    .filter { connection.allowedTools == null || it.name in connection.allowedTools }
                return result(id, buildJsonObject { put("tools", JsonArray(tools.map { it.definition })) })
            }
            val name = params["name"] as? JsonPrimitive
            if (name?.isString != true || name.content.isBlank()) {
                errorCode = "invalid_params"; return rpcError(id, -32602, "A tool name is required")
            }
            toolName = name.content
            if (connection.allowedTools != null && toolName !in connection.allowedTools) {
                errorCode = "tool_disabled"; return rpcError(id, -32602, "Tool is not shared")
            }
            val arguments = when (val value = params["arguments"]) {
                null -> buildJsonObject {}
                is JsonObject -> value
                else -> { errorCode = "invalid_params"; return rpcError(id, -32602, "Tool arguments must be an object") }
            }
            val output = session.client.callTool(toolName, arguments, operation)
            success = (output["isError"] as? JsonPrimitive)?.booleanOrNull != true
            if (!success) errorCode = "tool_error"
            return result(id, output)
        } catch (error: McpException) {
            errorCode = error.code?.let { "rpc_$it" } ?: error.httpStatus?.let { "http_$it" } ?: "protocol_error"
            if (error.httpStatus == 404) {
                removeSession(sessionId!!, session)
                return reject(404, "Upstream MCP session ended; initialize a new session")
            }
            return rpcError(id, error.code ?: -32001, error.message ?: "MCP request failed", data = error.data)
        } catch (_: IOException) {
            errorCode = "connection_failed"
            return rpcError(id, -32001, "Could not reach the upstream MCP server")
        } finally {
            session.requests.remove(id.toString(), operation)
            requestSlots.release()
            if (method == "tools/call") {
                try {
                    onCall(McpCallEvent(connection.id, connection.name, toolName, success,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), errorCode, startedAt))
                } catch (error: RuntimeException) {
                    java.util.logging.Logger.getLogger("PathPilotMcp").warning("MCP observer failed: ${error.javaClass.simpleName}")
                }
            }
        }
    }

    private fun initialize(connection: McpConnection, accessKey: String, id: JsonElement, params: JsonObject): Response {
        if ((params["protocolVersion"] as? JsonPrimitive)?.isString != true || params["capabilities"] !is JsonObject || params["clientInfo"] !is JsonObject)
            return rpcError(id, -32602, "Initialize requires protocolVersion, capabilities and clientInfo")
        if (!requestSlots.tryAcquire()) return rpcError(id, -32000, "MCP gateway is busy", 429)
        if (!sessionSlots.tryAcquire()) {
            requestSlots.release(); return rpcError(id, -32000, "MCP gateway has 32 sessions; close an unused session", 429)
        }
        val client = try { McpClient(connection, proxyProvider, httpClient) }
        catch (error: IllegalArgumentException) {
            requestSlots.release(); sessionSlots.release()
            return rpcError(id, -32602, error.message ?: "Invalid MCP connection")
        }
        initializing += client
        var retained = false
        try {
            if (stopped.get()) throw McpException("MCP sharing is stopped", code = -32800)
            client.connect()
            val sessionId = UUID.randomUUID().toString()
            synchronized(lifecycle) {
                if (stopped.get()) throw McpException("MCP sharing is stopped", code = -32800)
                sessions[sessionId] = Session(connection, client, accessKey)
                retained = true
            }
            return result(id, buildJsonObject {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
                put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
                put("serverInfo", buildJsonObject { put("name", "PathPilot"); put("version", "1") })
            }).also { it.addHeader("Mcp-Session-Id", sessionId) }
        } catch (error: McpException) {
            return rpcError(id, error.code ?: -32001, error.message ?: "MCP initialization failed", data = error.data)
        } catch (_: IOException) { return rpcError(id, -32001, "Could not reach the upstream MCP server") }
        finally {
            initializing -= client
            requestSlots.release()
            if (!retained) { sessionSlots.release(); client.close() }
        }
    }

    override fun stop() {
        synchronized(lifecycle) {
            stopped.set(true)
            initializing.forEach(McpClient::close)
            sessions.entries.forEach { (id, session) -> removeSession(id, session) }
        }
        super.stop()
    }

    private fun expireSessions() {
        val before = System.nanoTime() - TimeUnit.MINUTES.toNanos(30)
        sessions.entries.filter { it.value.requests.isEmpty() && it.value.lastAccess < before }
            .forEach { (id, session) -> removeSession(id, session) }
    }

    private fun removeSession(id: String, session: Session) {
        if (sessions.remove(id, session)) { sessionSlots.release(); session.close() }
    }

    private fun validOrigin(origin: String?): Boolean {
        if (origin == null) return true
        val url = origin.toHttpUrlOrNull() ?: return false
        if (url.scheme != "http" || url.port != listeningPort || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.encodedPath != "/" || url.query != null || url.fragment != null) return false
        if (url.host in setOf("localhost", "127.0.0.1", "::1")) return true
        val interfaces = try { NetworkInterface.getNetworkInterfaces() } catch (_: SocketException) { return false }
        return interfaces?.toList()?.flatMap { it.inetAddresses.toList() }?.any { it.hostAddress?.substringBefore('%') == url.host } == true
    }

    private fun result(id: JsonElement, output: JsonObject) = jsonResponse(200, buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("result", output)
    })

    private fun rpcError(id: JsonElement, code: Int, message: String, status: Int = 200, data: JsonElement? = null) = jsonResponse(status, buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id)
        put("error", buildJsonObject { put("code", code); put("message", message); data?.let { put("data", it) } })
    })

    private fun reject(status: Int, message: String) = jsonResponse(status, buildJsonObject { put("error", message) }).also {
        it.addHeader("Connection", "close"); it.setKeepAlive(false)
    }

    private fun jsonResponse(status: Int, value: JsonObject): Response = newFixedLengthResponse(
        Response.Status.lookup(status) ?: Response.Status.INTERNAL_ERROR, "application/json", value.toString(),
    )

    companion object { private const val MAX_REQUEST_BYTES = 2L * 1024 * 1024 }
}
