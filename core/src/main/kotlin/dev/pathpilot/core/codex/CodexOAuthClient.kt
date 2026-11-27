package dev.pathpilot.core.codex

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import dev.pathpilot.core.network.OutboundHttpClientFactory
import dev.pathpilot.core.network.OutboundProxyConfig
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Persist only in the Android credential vault. Never put this value in request logs. */
@Serializable
data class CodexTokens(
    val accessToken: String,
    val refreshToken: String,
    val idToken: String = "",
    val accountId: String,
    val expiresAtEpochSeconds: Long,
) {
    fun needsRefresh(nowEpochSeconds: Long = System.currentTimeMillis() / 1000): Boolean =
        nowEpochSeconds >= expiresAtEpochSeconds - 120

    override fun toString(): String = "CodexTokens(<redacted>)"
}

class DeviceLoginChallenge(
    val verificationUrl: String,
    val userCode: String,
    val deviceAuthId: String,
    val intervalSeconds: Long,
    val expiresAtEpochSeconds: Long,
) {
    private val codeExchangeStarted = AtomicBoolean(false)
    internal val isConsumed: Boolean get() = codeExchangeStarted.get()
    internal fun consumeCode(): Boolean = codeExchangeStarted.compareAndSet(false, true)
    override fun toString(): String = "DeviceLoginChallenge(<redacted>)"
}

sealed interface DeviceLoginResult {
    data object Pending : DeviceLoginResult
    data class Authorized(val tokens: CodexTokens) : DeviceLoginResult
}

/**
 * Device authorization protocol from openai/codex, pinned in docs/codex-research.md.
 * All methods block: use Dispatchers.IO. The caller owns polling and encrypted storage.
 * cancel() stops active calls and prevents later calls on this instance.
 * No automatic retry of token POSTs: an interrupted exchange may have consumed a one-time code.
 */
class CodexOAuthClient(
    httpClient: OkHttpClient = defaultClient(),
    issuer: String = DEFAULT_ISSUER,
    private val clientId: String = CLIENT_ID,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    proxyProvider: () -> OutboundProxyConfig = { OutboundProxyConfig() },
) {
    private val issuer = issuer.trimEnd('/')
    private val clients = OutboundHttpClientFactory(httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(capTimeout(httpClient.connectTimeoutMillis, 15_000), TimeUnit.MILLISECONDS)
        .readTimeout(capTimeout(httpClient.readTimeoutMillis, 45_000), TimeUnit.MILLISECONDS)
        .writeTimeout(capTimeout(httpClient.writeTimeoutMillis, 45_000), TimeUnit.MILLISECONDS)
        .callTimeout(capTimeout(httpClient.callTimeoutMillis, 45_000), TimeUnit.MILLISECONDS)
        .build(), proxyProvider)
    private val cancelled = AtomicBoolean(false)
    private val calls = ConcurrentHashMap.newKeySet<Call>()

    init {
        require(this.issuer.startsWith("https://") || this.issuer.startsWith("http://localhost:") ||
            this.issuer.startsWith("http://127.0.0.1:")) { "OAuth issuer must use HTTPS" }
    }

    /** The Android caller binds the selected registered loopback port before opening this URL. */
    fun beginBrowserLogin(redirectUri: String = DEFAULT_BROWSER_REDIRECT): BrowserLoginChallenge {
        val stage = CodexAuthStage.BROWSER_CALLBACK
        if (cancelled.get()) throw failure(stage, CodexAuthError.CANCELLED)
        if (redirectUri !in BROWSER_REDIRECTS) {
            throw CodexAuthException(null, "浏览器登录回调必须使用本机 1455 或 1457 端口", CodexAuthError.INVALID_CONFIGURATION, stage)
        }
        val random = SecureRandom()
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val verifier = encoder.encodeToString(ByteArray(64).also(random::nextBytes))
        val state = encoder.encodeToString(ByteArray(32).also(random::nextBytes))
        val challenge = encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8)))
        val url = "$issuer/oauth/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            .addQueryParameter("scope", "openid profile email offline_access api.connectors.read api.connectors.invoke")
            .addQueryParameter("id_token_add_organizations", "true")
            .addQueryParameter("codex_cli_simplified_flow", "true")
            .addQueryParameter("originator", "codex_cli_rs")
            .build().toString()
        return BrowserLoginChallenge(url, redirectUri, nowEpochSeconds() + 15 * 60, state, verifier)
    }

    /** Validates this attempt before its single code exchange; never logs or echoes callback values. */
    fun completeBrowserLogin(challenge: BrowserLoginChallenge, callbackUrl: String): CodexTokens {
        val stage = CodexAuthStage.BROWSER_CALLBACK
        if (cancelled.get()) throw failure(stage, CodexAuthError.CANCELLED)
        if (nowEpochSeconds() >= challenge.expiresAtEpochSeconds) throw failure(stage, CodexAuthError.EXPIRED)
        val callback = challenge.validatedCallback(callbackUrl) ?: throw failure(stage, CodexAuthError.STATE_MISMATCH)
        if (callback.queryParameterNames.contains("error")) {
            challenge.consume()
            val error = callback.queryParameterValues("error").singleOrNull()?.takeIf { it.isNotBlank() }
                ?: throw invalidResponse(stage)
            throw failure(stage, if (error == "access_denied")
                CodexAuthError.ACCESS_DENIED else CodexAuthError.UPSTREAM)
        }
        val code = callback.queryParameterValues("code").singleOrNull()?.takeIf { it.isNotBlank() }
        if (code == null) { challenge.consume(); throw invalidResponse(stage) }
        if (!challenge.consume()) {
            throw CodexAuthException(null, "本次授权码已处理，请重新登录", CodexAuthError.EXPIRED, stage)
        }
        return exchangeAuthorizationCode(code, challenge.codeVerifier, challenge.redirectUri)
    }

    fun beginDeviceLogin(): DeviceLoginChallenge {
        val body = buildJsonObject { put("client_id", clientId) }
        val stage = CodexAuthStage.DEVICE_CODE
        val json = executeJson(post("/api/accounts/deviceauth/usercode", body), stage)
        val interval = when (val raw = json["interval"]) {
            null -> 5L
            is JsonPrimitive -> raw.content.trim().toLongOrNull()?.takeIf { it >= 0 }
                ?: throw invalidResponse(stage)
            else -> throw invalidResponse(stage)
        }
        return DeviceLoginChallenge(
            verificationUrl = "$issuer/codex/device",
            userCode = json.string("user_code") ?: json.string("usercode")
                ?: throw invalidResponse(stage),
            deviceAuthId = json.requiredString("device_auth_id", stage),
            // Official server returns a string; also accept a JSON number. Never busy poll.
            intervalSeconds = interval.coerceIn(1L, 900L),
            expiresAtEpochSeconds = nowEpochSeconds() + 15 * 60,
        )
    }

    fun pollDeviceLogin(challenge: DeviceLoginChallenge): DeviceLoginResult {
        if (cancelled.get()) throw failure(CodexAuthStage.DEVICE_POLL, CodexAuthError.CANCELLED)
        if (challenge.isConsumed) throw failure(CodexAuthStage.CODE_EXCHANGE, CodexAuthError.EXPIRED)
        if (nowEpochSeconds() >= challenge.expiresAtEpochSeconds) {
            throw failure(CodexAuthStage.DEVICE_POLL, CodexAuthError.EXPIRED)
        }
        val request = post("/api/accounts/deviceauth/token", buildJsonObject {
            put("device_auth_id", challenge.deviceAuthId)
            put("user_code", challenge.userCode)
        })
        val result = execute(request, CodexAuthStage.DEVICE_POLL) { response ->
            // This is the Codex-specific device protocol, not RFC 8628's 400 authorization_pending.
            if (response.code == 403 || response.code == 404) null
            else response.readJson(CodexAuthStage.DEVICE_POLL)
        } ?: return DeviceLoginResult.Pending
        val stage = CodexAuthStage.CODE_EXCHANGE
        val code = result.requiredString("authorization_code", stage)
        val verifier = result.requiredString("code_verifier", stage)
        if (!challenge.consumeCode()) throw failure(stage, CodexAuthError.EXPIRED)
        return DeviceLoginResult.Authorized(exchangeAuthorizationCode(
            code, verifier, "$issuer/deviceauth/callback"))
    }

    private fun exchangeAuthorizationCode(code: String, verifier: String, redirectUri: String): CodexTokens {
        val stage = CodexAuthStage.CODE_EXCHANGE
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code").add("client_id", clientId)
            .add("code", code).add("redirect_uri", redirectUri).add("code_verifier", verifier).build()
        return parseTokens(executeJson(Request.Builder().url("$issuer/oauth/token").post(form).build(), stage), stage = stage)
    }

    fun refresh(tokens: CodexTokens): CodexTokens {
        val request = post("/oauth/token", buildJsonObject {
            put("grant_type", "refresh_token")
            put("client_id", clientId)
            put("refresh_token", tokens.refreshToken)
            put("refresh_reason", "proactive_expiry")
        })
        val stage = CodexAuthStage.REFRESH
        val refreshed = parseTokens(executeJson(request, stage), tokens, stage)
        if (refreshed.accountId != tokens.accountId) {
            throw CodexAuthException(null, "Codex 账号已变更，请重新登录", CodexAuthError.UNAUTHORIZED, stage)
        }
        return refreshed
    }

    private fun post(path: String, body: JsonObject): Request = Request.Builder()
        .url("$issuer$path")
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()

    /** Also prevents a queued IO operation from starting after its login dialog has closed. */
    fun cancel() {
        cancelled.set(true)
        calls.forEach(Call::cancel)
    }

    private fun executeJson(request: Request, stage: CodexAuthStage): JsonObject =
        execute(request, stage) { it.readJson(stage) }

    private fun <T> execute(request: Request, stage: CodexAuthStage, read: (Response) -> T): T {
        if (cancelled.get()) throw failure(stage, CodexAuthError.CANCELLED)
        val call = try { clients.client().newCall(request) } catch (_: IllegalArgumentException) {
            throw failure(stage, CodexAuthError.INVALID_CONFIGURATION)
        }
        calls.add(call)
        try {
            if (cancelled.get()) { call.cancel(); throw failure(stage, CodexAuthError.CANCELLED) }
            return call.execute().use { response ->
                if (cancelled.get()) throw failure(stage, CodexAuthError.CANCELLED)
                read(response).also { if (cancelled.get()) throw failure(stage, CodexAuthError.CANCELLED) }
            }
        } catch (error: CodexAuthException) {
            throw error
        } catch (_: InterruptedIOException) {
            throw failure(stage, if (cancelled.get()) CodexAuthError.CANCELLED else CodexAuthError.TIMEOUT)
        } catch (_: IOException) {
            throw failure(stage, if (cancelled.get() || call.isCanceled()) CodexAuthError.CANCELLED else CodexAuthError.NETWORK)
        } finally { calls.remove(call) }
    }

    private fun Response.readJson(stage: CodexAuthStage): JsonObject {
        if (!isSuccessful) {
            val kind = when {
                stage == CodexAuthStage.DEVICE_CODE && code == 404 -> CodexAuthError.DEVICE_LOGIN_UNAVAILABLE
                code == 401 -> CodexAuthError.UNAUTHORIZED
                code == 403 -> CodexAuthError.FORBIDDEN
                code == 429 -> CodexAuthError.RATE_LIMITED
                code == 400 && stage in setOf(CodexAuthStage.CODE_EXCHANGE, CodexAuthStage.REFRESH) &&
                    hasInvalidGrant() -> CodexAuthError.UNAUTHORIZED
                else -> CodexAuthError.UPSTREAM
            }
            throw failure(stage, kind, code, retryAfter(header("Retry-After")))
        }
        val content = body ?: throw invalidResponse(stage)
        val source = content.source()
        source.request(MAX_AUTH_RESPONSE_BYTES + 1)
        if (source.buffer.size > MAX_AUTH_RESPONSE_BYTES) throw invalidResponse(stage)
        return try {
            Json.parseToJsonElement(source.readUtf8()) as? JsonObject ?: throw invalidResponse(stage)
        } catch (_: SerializationException) {
            throw invalidResponse(stage)
        }
    }

    private fun Response.hasInvalidGrant(): Boolean {
        val source = body?.source() ?: return false
        source.request(MAX_ERROR_RESPONSE_BYTES + 1)
        if (source.buffer.size > MAX_ERROR_RESPONSE_BYTES) return false
        val value = try { Json.parseToJsonElement(source.readUtf8()) as? JsonObject }
            catch (_: SerializationException) { return false }
        val error = value?.get("error")
        val errorCode = when (error) {
            is JsonPrimitive -> error.takeIf { it.isString }?.content
            is JsonObject -> error.string("code")
            else -> null
        }
        return errorCode in setOf("invalid_grant", "refresh_token_expired", "refresh_token_reused", "refresh_token_invalidated")
    }

    private fun retryAfter(value: String?): Long? {
        if (value == null) return null
        value.trim().toLongOrNull()?.takeIf { it >= 0 }?.let { return it.coerceAtMost(900L) }
        return try {
            (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() - nowEpochSeconds())
                .coerceIn(0L, 900L)
        } catch (_: DateTimeParseException) { null }
    }

    private fun parseTokens(json: JsonObject, previous: CodexTokens? = null, stage: CodexAuthStage): CodexTokens {
        val accessToken = json.string("access_token") ?: throw invalidResponse(stage)
        val idToken = json.string("id_token") ?: previous?.idToken.orEmpty()
        val accessClaims = decodeClaims(accessToken)
        val idClaims = decodeClaims(idToken)
        // Claims are routing/expiry hints from the TLS-authenticated OAuth response, not locally
        // verified authorization. The upstream validates the access token on every actual request.
        val accountId = accountId(accessClaims) ?: accountId(idClaims) ?: previous?.accountId
            ?: throw CodexAuthException(null, "登录响应缺少 ChatGPT 账号信息，请重新授权", CodexAuthError.INVALID_RESPONSE, stage)
        val expiresIn = (json["expires_in"] as? JsonPrimitive)?.longOrNull
        val expiry = (accessClaims["exp"] as? JsonPrimitive)?.longOrNull
            ?: expiresIn?.let { nowEpochSeconds() + it }
            ?: if (previous != null && accessToken == previous.accessToken) previous.expiresAtEpochSeconds
            else nowEpochSeconds() + 60 * 60
        return CodexTokens(accessToken, json.string("refresh_token") ?: previous?.refreshToken
            ?: throw invalidResponse(stage), idToken, accountId, expiry)
    }

    private fun accountId(claims: JsonObject): String? =
        (claims["https://api.openai.com/auth"] as? JsonObject)?.string("chatgpt_account_id")

    private fun decodeClaims(token: String): JsonObject = try {
        val payload = token.split('.').getOrNull(1) ?: ""
        Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)) as? JsonObject
            ?: JsonObject(emptyMap())
    } catch (_: IllegalArgumentException) { JsonObject(emptyMap()) }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.requiredString(key: String, stage: CodexAuthStage): String = string(key) ?: throw invalidResponse(stage)
    private fun invalidResponse(stage: CodexAuthStage) = failure(stage, CodexAuthError.INVALID_RESPONSE)

    private fun failure(stage: CodexAuthStage, kind: CodexAuthError, status: Int? = null, retryAfter: Long? = null): CodexAuthException {
        val operation = when (stage) {
            CodexAuthStage.DEVICE_CODE -> "获取设备码"
            CodexAuthStage.DEVICE_POLL -> "等待授权"
            CodexAuthStage.CODE_EXCHANGE -> "交换登录凭据"
            CodexAuthStage.REFRESH -> "刷新登录"
            CodexAuthStage.BROWSER_CALLBACK -> "浏览器授权"
        }
        val message = when (kind) {
            CodexAuthError.NETWORK -> "$operation 时连接中断，请检查网络或代理"
            CodexAuthError.TIMEOUT -> "$operation 超时，请重试"
            CodexAuthError.RATE_LIMITED -> "授权请求过于频繁，请稍后重试"
            CodexAuthError.UNAUTHORIZED -> "Codex 登录已过期或被拒绝，请重新授权"
            CodexAuthError.FORBIDDEN -> "当前账号无法完成授权，请检查账号的 Codex 访问权限"
            CodexAuthError.DEVICE_LOGIN_UNAVAILABLE -> "此服务器未开放设备码登录，请改用浏览器登录"
            CodexAuthError.EXPIRED -> "本次授权已过期，请重新登录"
            CodexAuthError.INVALID_RESPONSE -> "无法识别 Codex 授权响应，请重试"
            CodexAuthError.INVALID_CONFIGURATION -> "代理配置无效，请检查代理主机和端口"
            CodexAuthError.CANCELLED -> "已取消登录"
            CodexAuthError.STATE_MISMATCH -> "浏览器回调与本次登录不匹配"
            CodexAuthError.ACCESS_DENIED -> "浏览器授权未获批准，请重新登录"
            CodexAuthError.UPSTREAM -> "$operation 失败${status?.let { "（HTTP $it）" }.orEmpty()}"
        }
        return CodexAuthException(status, message, kind, stage, retryAfter)
    }

    companion object {
        const val DEFAULT_ISSUER = "https://auth.openai.com"
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val DEFAULT_BROWSER_REDIRECT = "http://127.0.0.1:1455/auth/callback"
        const val FALLBACK_BROWSER_REDIRECT = "http://127.0.0.1:1457/auth/callback"
        private val BROWSER_REDIRECTS = setOf(DEFAULT_BROWSER_REDIRECT, FALLBACK_BROWSER_REDIRECT)
        private const val MAX_AUTH_RESPONSE_BYTES = 1024L * 1024
        private const val MAX_ERROR_RESPONSE_BYTES = 64L * 1024
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
        private fun capTimeout(value: Int, maximum: Int): Long = if (value in 1..maximum) value.toLong() else maximum.toLong()
    }
}
