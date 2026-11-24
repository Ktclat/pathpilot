package dev.pathpilot.core.codex

import kotlinx.serialization.Serializable
import dev.pathpilot.core.network.OutboundHttpClientFactory
import dev.pathpilot.core.network.OutboundProxyConfig
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

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
    override fun toString(): String = "DeviceLoginChallenge(<redacted>)"
}

sealed interface DeviceLoginResult {
    data object Pending : DeviceLoginResult
    data class Authorized(val tokens: CodexTokens) : DeviceLoginResult
}

/** Safe for UI display: contains an operation and HTTP status, never upstream response text. */
class CodexAuthException(val httpStatus: Int?, message: String) : IOException(message)

/**
 * Device authorization protocol from openai/codex, pinned in docs/codex-research.md.
 * All methods block: use Dispatchers.IO. The caller owns polling, cancellation and encrypted storage.
 * No automatic retry of token POSTs: an interrupted exchange may have consumed a one-time code.
 */
class CodexOAuthClient(
    httpClient: OkHttpClient = OkHttpClient(),
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
        .connectTimeout(15, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build(), proxyProvider)

    init {
        require(this.issuer.startsWith("https://") || this.issuer.startsWith("http://localhost:") ||
            this.issuer.startsWith("http://127.0.0.1:")) { "OAuth issuer must use HTTPS" }
    }

    fun beginDeviceLogin(): DeviceLoginChallenge {
        val body = buildJsonObject { put("client_id", clientId) }
        val json = executeJson(post("/api/accounts/deviceauth/usercode", body), "Request device code")
        return DeviceLoginChallenge(
            verificationUrl = "$issuer/codex/device",
            userCode = json.string("user_code") ?: json.string("usercode")
                ?: throw invalidResponse(),
            deviceAuthId = json.requiredString("device_auth_id"),
            // Official server returns a string; also accept a JSON number. Never busy poll.
            intervalSeconds = (json["interval"]?.jsonPrimitive?.content?.toLongOrNull() ?: 5)
                .coerceAtLeast(1),
            expiresAtEpochSeconds = nowEpochSeconds() + 15 * 60,
        )
    }

    fun pollDeviceLogin(challenge: DeviceLoginChallenge): DeviceLoginResult {
        if (nowEpochSeconds() >= challenge.expiresAtEpochSeconds) {
            throw CodexAuthException(null, "Device code expired; start login again")
        }
        val request = post("/api/accounts/deviceauth/token", buildJsonObject {
            put("device_auth_id", challenge.deviceAuthId)
            put("user_code", challenge.userCode)
        })
        val result = clients.client().newCall(request).execute().use { response ->
            // This is the Codex-specific device protocol, not RFC 8628's 400 authorization_pending.
            if (response.code == 403 || response.code == 404) return DeviceLoginResult.Pending
            response.readJson("Poll device login")
        }
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", clientId)
            .add("code", result.requiredString("authorization_code"))
            .add("redirect_uri", "$issuer/deviceauth/callback")
            .add("code_verifier", result.requiredString("code_verifier"))
            .build()
        val tokenJson = executeJson(Request.Builder().url("$issuer/oauth/token").post(form).build(),
            "Exchange device code")
        return DeviceLoginResult.Authorized(parseTokens(tokenJson))
    }

    fun refresh(tokens: CodexTokens): CodexTokens {
        val request = post("/oauth/token", buildJsonObject {
            put("grant_type", "refresh_token")
            put("client_id", clientId)
            put("refresh_token", tokens.refreshToken)
            put("refresh_reason", "proactive_expiry")
        })
        val refreshed = parseTokens(executeJson(request, "Refresh Codex login"), tokens)
        if (refreshed.accountId != tokens.accountId) {
            throw CodexAuthException(null, "Refreshed account changed; sign in again")
        }
        return refreshed
    }

    private fun post(path: String, body: JsonObject): Request = Request.Builder()
        .url("$issuer$path")
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()

    private fun executeJson(request: Request, operation: String): JsonObject =
        clients.client().newCall(request).execute().use { it.readJson(operation) }

    private fun Response.readJson(operation: String): JsonObject {
        if (!isSuccessful) {
            val detail = when {
                operation == "Request device code" && code == 404 ->
                    "Device login is unavailable for this server"
                code == 401 -> "Login expired or rejected; sign in again"
                code == 429 -> "Too many requests; wait before retrying"
                else -> "$operation failed (HTTP $code)"
            }
            throw CodexAuthException(code, detail)
        }
        val content = body ?: throw invalidResponse()
        val source = content.source()
        source.request(MAX_AUTH_RESPONSE_BYTES + 1)
        if (source.buffer.size > MAX_AUTH_RESPONSE_BYTES) throw invalidResponse()
        return try {
            Json.parseToJsonElement(source.readUtf8()).jsonObject
        } catch (_: IllegalArgumentException) {
            throw invalidResponse()
        }
    }

    private fun parseTokens(json: JsonObject, previous: CodexTokens? = null): CodexTokens {
        val accessToken = json.string("access_token") ?: throw invalidResponse()
        val idToken = json.string("id_token") ?: previous?.idToken.orEmpty()
        val accessClaims = decodeClaims(accessToken)
        val idClaims = decodeClaims(idToken)
        // Claims are routing/expiry hints from the TLS-authenticated OAuth response, not locally
        // verified authorization. The upstream validates the access token on every actual request.
        val accountId = accountId(accessClaims) ?: accountId(idClaims) ?: previous?.accountId
            ?: throw CodexAuthException(null, "Codex login did not return a ChatGPT account ID")
        val expiresIn = (json["expires_in"] as? JsonPrimitive)?.longOrNull
        val expiry = (accessClaims["exp"] as? JsonPrimitive)?.longOrNull
            ?: expiresIn?.let { nowEpochSeconds() + it }
            ?: if (previous != null && accessToken == previous.accessToken) previous.expiresAtEpochSeconds
            else nowEpochSeconds() + 60 * 60
        return CodexTokens(accessToken, json.string("refresh_token") ?: previous?.refreshToken
            ?: throw invalidResponse(), idToken, accountId, expiry)
    }

    private fun accountId(claims: JsonObject): String? =
        (claims["https://api.openai.com/auth"] as? JsonObject)?.string("chatgpt_account_id")

    private fun decodeClaims(token: String): JsonObject = try {
        val payload = token.split('.').getOrNull(1) ?: ""
        Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)).jsonObject
    } catch (_: Exception) { JsonObject(emptyMap()) }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.requiredString(key: String): String = string(key) ?: throw invalidResponse()
    private fun invalidResponse() = CodexAuthException(null, "Invalid response from Codex authentication")

    companion object {
        const val DEFAULT_ISSUER = "https://auth.openai.com"
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        private const val MAX_AUTH_RESPONSE_BYTES = 1024L * 1024
    }
}
