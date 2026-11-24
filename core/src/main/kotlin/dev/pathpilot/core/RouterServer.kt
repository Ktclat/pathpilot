package dev.pathpilot.core

import dev.pathpilot.core.codex.CodexBackend
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** Local API gateway. Configuration is snapshotted once for every request. */
class RouterServer(
    private val configProvider: () -> RouterConfig,
    private val client: OkHttpClient = defaultClient(),
    private val onRequest: (RequestEvent) -> Unit = {},
    private val codexCredentials: (ProviderConfig) -> CodexCredentials = {
        CodexCredentials(it.apiKey, it.codexAccountId)
    },
) : NanoHTTPD("0.0.0.0", configProvider().port) {
    private val json = Json { ignoreUnknownKeys = true }
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()
    private val requestSlots = Semaphore(8)
    private val transport = client.newBuilder()
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()

    // NanoHTTPD's default GZIPOutputStream buffers without SYNC_FLUSH, which stalls SSE.
    override fun useGzipWhenAccepted(response: Response): Boolean =
        !response.mimeType.orEmpty().startsWith("text/event-stream") && super.useGzipWhenAccepted(response)

    override fun serve(session: IHTTPSession): Response {
        if (session.uri == "/health" && session.method == Method.GET) {
            return jsonResponse(200, buildJsonObject { put("status", "ok") })
        }
        val config = configProvider()
        val supplied = session.headers["authorization"]?.takeIf { it.startsWith("Bearer ", true) }?.substring(7).orEmpty()
        if (!AccessKeys.matches(config.accessKey, supplied)) return error(401, "invalid_api_key", "A valid local Bearer access key is required.")
        if (session.uri == "/v1/models" && session.method == Method.GET) {
            val providers = config.providers.filter { it.enabled }.associateBy { it.id }
            return jsonResponse(200, buildJsonObject {
                put("object", "list")
                put("data", buildJsonArray {
                    config.routes.filter { it.enabled && providers.containsKey(it.providerId) }.forEach { route -> add(buildJsonObject {
                        put("id", route.alias); put("object", "model"); put("created", 0); put("owned_by", "pathpilot")
                    }) }
                })
            })
        }
        if (session.uri !in setOf("/v1/chat/completions", "/v1/responses")) return error(404, "not_found", "Unknown API endpoint.")
        if (session.method != Method.POST) return error(405, "method_not_allowed", "Use POST for this endpoint.")
        if (session.headers["transfer-encoding"]?.isNotBlank() == true) return error(411, "length_required", "Send a JSON request with Content-Length; chunked request bodies are not supported.")
        val length = session.headers["content-length"]?.toLongOrNull() ?: return error(411, "length_required", "Content-Length is required.")
        if (length !in 1..MAX_REQUEST_BYTES) return error(if (length > MAX_REQUEST_BYTES) 413 else 400, "invalid_request", "JSON request body must be between 1 byte and 2 MiB.")
        if (session.headers["content-encoding"]?.let { it != "identity" } == true) return error(415, "unsupported_media_type", "Compressed request bodies are not supported.")
        if (session.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() != "application/json") return error(415, "unsupported_media_type", "Content-Type must be application/json.")
        val body = try {
            json.parseToJsonElement(readBody(session.inputStream, length.toInt()).toString(Charsets.UTF_8)) as? JsonObject
                ?: return error(400, "invalid_json", "Request body must be a JSON object.")
        } catch (_: Exception) { return error(400, "invalid_json", "Invalid or incomplete JSON request.") }
        val alias = (body["model"] as? JsonPrimitive)?.contentOrNull ?: return error(400, "model_required", "A model alias is required.")
        val route = config.routes.firstOrNull { it.enabled && it.alias == alias } ?: return error(404, "model_not_found", "Unknown or disabled model alias.")
        val provider = config.providers.firstOrNull { it.enabled && it.id == route.providerId } ?: return error(503, "provider_unavailable", "The selected provider is unavailable.")
        if (!requestSlots.tryAcquire()) return error(429, "router_busy", "The phone is already handling 8 requests. Retry after a request completes.")
        val started = System.nanoTime()
        var call: Call? = null
        val reported = AtomicBoolean(false)
        fun report(status: Int) {
            if (reported.compareAndSet(false, true)) {
                requestSlots.release()
                runCatching { onRequest(RequestEvent(alias, provider.id, status, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), session.uri)) }
            }
        }
        try {
            val streamValue = body["stream"]
            require(streamValue == null || streamValue is JsonPrimitive && streamValue.booleanOrNull != null) { "stream must be a boolean." }
            val streaming = streamValue?.jsonPrimitive?.booleanOrNull == true
            val routedBody = JsonObject(body + ("model" to JsonPrimitive(route.upstreamModel)))
            val codex = provider.kind == ProviderKind.CODEX
            val chat = session.uri == "/v1/chat/completions"
            if (provider.kind == ProviderKind.DEEPSEEK && !chat) {
                report(400)
                return error(400, "unsupported_endpoint", "DeepSeek routes support /v1/chat/completions. Use a Codex or Responses-compatible provider for /v1/responses.")
            }
            val request = if (codex) {
                val credentials = codexCredentials(provider)
                if (credentials.accessToken.isBlank()) { report(503); return error(503, "account_required", "Sign in to the Codex provider on the phone.") }
                CodexBackend.prepare(if (chat) ResponseBridge.chatToResponses(routedBody) else routedBody, credentials.accessToken, credentials.accountId)
            } else {
                if (provider.apiKey.isBlank()) { report(503); return error(503, "api_key_required", "Configure this provider's API key on the phone.") }
                val endpoint = provider.baseUrl.trimEnd('/') + if (chat) "/chat/completions" else "/responses"
                Request.Builder().url(endpoint)
                    .header("Authorization", "Bearer ${provider.apiKey}")
                    .header("Accept", if (streaming) "text/event-stream" else "application/json")
                    .post(routedBody.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
            }
            call = transport.newCall(request)
            activeCalls.add(call)
            val upstream = call.execute()
            if (!upstream.isSuccessful) {
                val status = upstream.code
                val retryAfter = upstream.header("Retry-After")?.takeIf { it.matches(Regex("[0-9]{1,8}")) }
                upstream.close(); activeCalls.remove(call); report(status)
                return error(status, "upstream_error", "Upstream returned HTTP $status. Check the provider account and model on the phone.").also {
                    retryAfter?.let { delay -> it.addHeader("Retry-After", delay) }
                }
            }
            val responseBody = upstream.body ?: throw IOException("Upstream response has no body.")
            if (codex && !streaming) {
                val final = upstream.use { ResponseBridge.aggregate(responseBody.byteStream()) }
                activeCalls.remove(call); report(200)
                return jsonResponse(200, if (chat) ResponseBridge.asChat(final, alias) else JsonObject(final + ("model" to JsonPrimitive(alias))))
            }
            val raw = responseBody.byteStream()
            val content = if (codex && chat) ResponseBridge.chatStream(raw, alias,
                (body["stream_options"] as? JsonObject)?.get("include_usage")?.jsonPrimitive?.booleanOrNull == true) else raw
            val stream = object : FilterInputStream(content) {
                private val closed = AtomicBoolean(false)
                override fun close() {
                    if (closed.compareAndSet(false, true)) {
                        try { super.close() } finally {
                            upstream.close(); call.cancel(); activeCalls.remove(call); report(upstream.code)
                        }
                    }
                }
            }
            return newChunkedResponse(status(upstream.code), if (streaming) "text/event-stream; charset=utf-8" else "application/json; charset=utf-8", stream).also {
                it.addHeader("Cache-Control", "no-store")
                it.addHeader("X-Accel-Buffering", "no")
            }
        } catch (_: SocketTimeoutException) {
            call?.cancel(); call?.let(activeCalls::remove); report(504)
            return error(504, "upstream_timeout", "The upstream request timed out.")
        } catch (e: IllegalArgumentException) {
            call?.cancel(); call?.let(activeCalls::remove); report(400)
            // Locally authored validation messages only; never return arbitrary exception content.
            return error(400, "unsupported_request", "Invalid upstream URL or unsupported request fields for this provider. Check the API compatibility documentation.")
        } catch (_: Exception) {
            call?.cancel(); call?.let(activeCalls::remove); report(502)
            return error(502, "upstream_unavailable", "Could not complete the upstream request. Check network access and provider login on the phone.")
        }
    }

    override fun stop() {
        activeCalls.forEach { it.cancel() }
        activeCalls.clear()
        super.stop()
    }

    private fun readBody(stream: InputStream, length: Int): ByteArray {
        val output = ByteArrayOutputStream(length)
        val buffer = ByteArray(8192)
        var remaining = length
        while (remaining > 0) {
            val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) throw IOException("Incomplete request.")
            output.write(buffer, 0, read); remaining -= read
        }
        return output.toByteArray()
    }

    private fun error(code: Int, type: String, message: String) = jsonResponse(code, buildJsonObject {
        put("error", buildJsonObject { put("type", type); put("message", message); put("code", type) })
    }).also { if (code == 401) it.addHeader("WWW-Authenticate", "Bearer") }

    private fun jsonResponse(code: Int, body: JsonObject) = newFixedLengthResponse(status(code), "application/json; charset=utf-8", body.toString()).also {
        it.addHeader("Cache-Control", "no-store")
    }

    private fun status(code: Int): Response.IStatus = Response.Status.lookup(code) ?: object : Response.IStatus {
        override fun getRequestStatus() = code
        override fun getDescription() = "$code Upstream"
    }

    companion object {
        private const val MAX_REQUEST_BYTES = 2L * 1024 * 1024
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
