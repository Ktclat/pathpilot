package dev.pathpilot.core

import dev.pathpilot.core.codex.CodexBackend
import dev.pathpilot.core.network.OutboundHttpClientFactory
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
    private val transport = OutboundHttpClientFactory(client)

    // NanoHTTPD's default GZIPOutputStream buffers without SYNC_FLUSH, which stalls SSE.
    override fun useGzipWhenAccepted(response: Response): Boolean =
        !response.mimeType.orEmpty().startsWith("text/event-stream") && super.useGzipWhenAccepted(response)

    override fun serve(session: IHTTPSession): Response {
        if (session.uri == "/health" && session.method == Method.GET) {
            return jsonResponse(200, buildJsonObject { put("status", "ok") }).closeIfBodyUnread(session)
        }
        val config = configProvider()
        val supplied = session.headers["authorization"]?.takeIf { it.startsWith("Bearer ", true) }?.substring(7).orEmpty()
        if (!AccessKeys.matches(config.accessKey, supplied)) return rejectBeforeBody(401, "invalid_api_key", "A valid local Bearer access key is required.")
        if (session.uri == "/v1/models" && session.method == Method.GET) {
            val providers = config.providers.filter { it.enabled }.associateBy { it.id }
            return jsonResponse(200, buildJsonObject {
                put("object", "list")
                put("data", buildJsonArray {
                    config.routes.filter { it.enabled && providers.containsKey(it.providerId) }.forEach { route -> add(buildJsonObject {
                        put("id", route.alias); put("object", "model"); put("created", 0); put("owned_by", "pathpilot")
                    }) }
                })
            }).closeIfBodyUnread(session)
        }
        if (session.uri !in setOf("/v1/chat/completions", "/v1/responses")) return rejectBeforeBody(404, "not_found", "Unknown API endpoint.")
        if (session.method != Method.POST) return rejectBeforeBody(405, "method_not_allowed", "Use POST for this endpoint.")
        val started = System.nanoTime()
        val startedAt = System.currentTimeMillis()
        var alias = ""
        var route: RouteConfig? = null
        var provider: ProviderConfig? = null
        var streaming = false
        var slotAcquired = false
        var call: Call? = null
        var observation: ResponseObservation? = null
        val reported = AtomicBoolean(false)
        fun report(status: Int, outcome: RequestOutcome = if (status in 200..299) RequestOutcome.SUCCESS else RequestOutcome.HTTP_ERROR,
                   code: String? = null) {
            if (reported.compareAndSet(false, true)) {
                if (slotAcquired) requestSlots.release()
                runCatching { onRequest(RequestEvent(
                    model = alias.take(128), providerId = provider?.id ?: route?.providerId.orEmpty(),
                    status = status, durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    endpoint = session.uri, startedAtEpochMs = startedAt, providerName = provider?.name.orEmpty(),
                    upstreamModel = route?.upstreamModel.orEmpty(), streaming = streaming,
                    usage = observation?.usage, outcome = outcome, errorCode = code,
                )) }
            }
        }
        fun reject(code: Int, type: String, message: String, unread: Boolean = false): Response {
            report(code, code = type)
            return if (unread) rejectBeforeBody(code, type, message) else error(code, type, message)
        }
        if (session.headers["transfer-encoding"]?.isNotBlank() == true) return reject(411, "length_required", "Send a JSON request with Content-Length; chunked request bodies are not supported.", true)
        val length = session.headers["content-length"]?.toLongOrNull() ?: return reject(411, "length_required", "Content-Length is required.", true)
        if (length !in 1..MAX_REQUEST_BYTES) return reject(if (length > MAX_REQUEST_BYTES) 413 else 400, "invalid_request", "JSON request body must be between 1 byte and 2 MiB.", true)
        if (session.headers["content-encoding"]?.let { it != "identity" } == true) return reject(415, "unsupported_media_type", "Compressed request bodies are not supported.", true)
        if (session.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() != "application/json") return reject(415, "unsupported_media_type", "Content-Type must be application/json.", true)
        val body = try {
            json.parseToJsonElement(readBody(session.inputStream, length.toInt()).toString(Charsets.UTF_8)) as? JsonObject
                ?: return reject(400, "invalid_json", "Request body must be a JSON object.", true)
        } catch (_: Exception) { return reject(400, "invalid_json", "Invalid or incomplete JSON request.", true) }
        alias = (body["model"] as? JsonPrimitive)?.contentOrNull ?: return reject(400, "model_required", "A model alias is required.")
        streaming = (body["stream"] as? JsonPrimitive)?.booleanOrNull == true
        val selectedRoute = config.routes.firstOrNull { it.alias == alias }
        route = selectedRoute
        val selectedProvider = config.providers.firstOrNull { it.id == selectedRoute?.providerId }
        provider = selectedProvider
        if (selectedRoute == null || !selectedRoute.enabled) return reject(404, "model_not_found", "Unknown or disabled model alias.")
        if (selectedProvider == null || !selectedProvider.enabled) return reject(503, "provider_unavailable", "The selected provider is unavailable.")
        if (!requestSlots.tryAcquire()) return reject(429, "router_busy", "The phone is already handling 8 requests. Retry after a request completes.")
        slotAcquired = true
        try {
            val streamValue = body["stream"]
            require(streamValue == null || streamValue is JsonPrimitive && streamValue.booleanOrNull != null) { "stream must be a boolean." }
            val routedBody = JsonObject(body + ("model" to JsonPrimitive(selectedRoute.upstreamModel)))
            val codex = selectedProvider.kind == ProviderKind.CODEX
            val chat = session.uri == "/v1/chat/completions"
            if (selectedProvider.kind == ProviderKind.DEEPSEEK && !chat) {
                return reject(400, "unsupported_endpoint", "DeepSeek routes support /v1/chat/completions. Use a Codex or Responses-compatible provider for /v1/responses.")
            }
            val request = if (codex) {
                val credentials = codexCredentials(selectedProvider)
                if (credentials.accessToken.isBlank()) return reject(503, "account_required", "Sign in to the Codex provider on the phone.")
                CodexBackend.prepare(if (chat) ResponseBridge.chatToResponses(routedBody) else routedBody, credentials.accessToken, credentials.accountId)
            } else {
                if (selectedProvider.apiKey.isBlank() && selectedProvider.kind != ProviderKind.OPENAI_COMPATIBLE) {
                    return reject(503, "api_key_required", "Configure this provider's API key on the phone.")
                }
                val endpoint = selectedProvider.baseUrl.trimEnd('/') + if (chat) "/chat/completions" else "/responses"
                Request.Builder().url(endpoint)
                    .apply { if (selectedProvider.apiKey.isNotBlank()) header("Authorization", "Bearer ${selectedProvider.apiKey.trim()}") }
                    .header("Accept", if (streaming) "text/event-stream" else "application/json")
                    .post(routedBody.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
            }
            call = transport.client(config.outboundProxy).newCall(request)
            activeCalls.add(call)
            val upstream = call.execute()
            if (!upstream.isSuccessful) {
                val status = upstream.code
                val retryAfter = upstream.header("Retry-After")?.takeIf { it.matches(Regex("[0-9]{1,8}")) }
                upstream.close(); activeCalls.remove(call); report(status, code = "upstream_error")
                return error(status, "upstream_error", "Upstream returned HTTP $status. Check the provider account and model on the phone.").also {
                    retryAfter?.let { delay -> it.addHeader("Retry-After", delay) }
                }
            }
            val responseBody = upstream.body ?: throw IOException("Upstream response has no body.")
            val observed = ResponseObservation(sse = codex || streaming)
            observation = observed
            val raw = observed.wrap(responseBody.byteStream())
            if (codex && !streaming) {
                val final = upstream.use { ResponseBridge.aggregate(raw) }
                activeCalls.remove(call)
                val response = jsonResponse(200, if (chat) ResponseBridge.asChat(final, alias)
                    else JsonObject(final + ("model" to JsonPrimitive(alias))))
                report(200, if (observed.incomplete) RequestOutcome.INTERRUPTED
                    else if (observed.errorCode != null) RequestOutcome.HTTP_ERROR else RequestOutcome.SUCCESS, observed.errorCode)
                return response
            }
            val content = if (codex && chat) ResponseBridge.chatStream(raw, alias,
                (body["stream_options"] as? JsonObject)?.get("include_usage")?.jsonPrimitive?.booleanOrNull == true) else raw
            val stream = object : FilterInputStream(content) {
                private val closed = AtomicBoolean(false)
                private var eof = false
                private var readFailure = false
                private var readTimeout = false

                private inline fun readObserved(read: () -> Int): Int = try {
                    read().also { if (it < 0) eof = true }
                } catch (timeout: SocketTimeoutException) {
                    readFailure = true; readTimeout = true; throw timeout
                } catch (failure: IOException) {
                    readFailure = true; throw failure
                }
                override fun read(): Int = readObserved { `in`.read() }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                    readObserved { `in`.read(bytes, offset, length) }

                override fun close() {
                    if (closed.compareAndSet(false, true)) {
                        val interrupted = !eof || readFailure || observed.incomplete || (streaming && !observed.terminal)
                        val outcome = if (interrupted) RequestOutcome.INTERRUPTED
                            else if (observed.errorCode != null) RequestOutcome.HTTP_ERROR else RequestOutcome.SUCCESS
                        val code = when {
                            readTimeout -> "upstream_timeout"
                            !eof || readFailure -> if (call.isCanceled()) "request_cancelled" else "stream_interrupted"
                            observed.errorCode != null -> observed.errorCode
                            streaming && !observed.terminal -> "upstream_stream_incomplete"
                            else -> null
                        }
                        try { super.close() } finally {
                            try { upstream.close() } finally {
                                call.cancel(); activeCalls.remove(call); report(upstream.code, outcome, code)
                            }
                        }
                    }
                }
            }
            return newChunkedResponse(status(upstream.code), if (streaming) "text/event-stream; charset=utf-8" else "application/json; charset=utf-8", stream).also {
                it.addHeader("Cache-Control", "no-store")
                it.addHeader("X-Accel-Buffering", "no")
            }
        } catch (_: SocketTimeoutException) {
            call?.cancel(); call?.let(activeCalls::remove); report(504, code = "upstream_timeout")
            return error(504, "upstream_timeout", "The upstream request timed out.")
        } catch (_: IllegalArgumentException) {
            call?.cancel(); call?.let(activeCalls::remove); report(400, code = "unsupported_request")
            return error(400, "unsupported_request", "Invalid upstream URL or unsupported request fields for this provider. Check the API compatibility documentation.")
        } catch (_: Exception) {
            val cancelled = call?.isCanceled() == true
            call?.cancel(); call?.let(activeCalls::remove)
            report(502, if (cancelled) RequestOutcome.INTERRUPTED else RequestOutcome.HTTP_ERROR,
                if (cancelled) "request_cancelled" else observation?.errorCode ?: "upstream_unavailable")
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

    /** NanoHTTPD does not drain request bodies: unread bytes must never become a new request. */
    private fun rejectBeforeBody(code: Int, type: String, message: String) =
        error(code, type, message).also { it.closeConnection(true) }

    private fun Response.closeIfBodyUnread(session: IHTTPSession): Response = apply {
        if (session.headers.containsKey("transfer-encoding") ||
            session.headers["content-length"]?.trim()?.let { it != "0" } == true) closeConnection(true)
    }

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
