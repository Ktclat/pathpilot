package dev.pathpilot.core

import kotlinx.serialization.Serializable
import dev.pathpilot.core.network.OutboundProxyConfig
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

@Serializable
enum class ProviderKind { CODEX, DEEPSEEK, OPENAI_COMPATIBLE }

@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val apiKey: String = "",
    val enabled: Boolean = true,
    val codexAccountId: String = "",
) {
    override fun toString() = "ProviderConfig(id=$id, name=$name, kind=$kind, enabled=$enabled, credentials=<redacted>)"
}

@Serializable
data class RouteConfig(
    val alias: String,
    val providerId: String,
    val upstreamModel: String,
    val enabled: Boolean = true,
)

@Serializable
data class RouterConfig(
    val port: Int = 8317,
    val accessKey: String,
    val providers: List<ProviderConfig> = emptyList(),
    val routes: List<RouteConfig> = emptyList(),
    val outboundProxy: OutboundProxyConfig = OutboundProxyConfig(),
) {
    override fun toString() = "RouterConfig(port=$port, accessKey=<redacted>, providers=$providers, routes=$routes)"
}

data class CodexCredentials(val accessToken: String, val accountId: String) {
    override fun toString() = "CodexCredentials(<redacted>)"
}

@Serializable
data class TokenUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val reasoningOutputTokens: Long? = null,
)

@Serializable
enum class RequestOutcome { SUCCESS, HTTP_ERROR, INTERRUPTED }

/** Metadata only: no prompts, generated content, credentials or upstream error bodies. */
@Serializable
data class RequestEvent(
    val model: String,
    val providerId: String,
    val status: Int,
    val durationMs: Long,
    val endpoint: String,
    val id: String = UUID.randomUUID().toString(),
    val startedAtEpochMs: Long = System.currentTimeMillis(),
    val providerName: String = providerId,
    val upstreamModel: String = model,
    val streaming: Boolean = false,
    val usage: TokenUsage? = null,
    val outcome: RequestOutcome = if (status in 200..299) RequestOutcome.SUCCESS else RequestOutcome.HTTP_ERROR,
    val errorCode: String? = null,
)

object AccessKeys {
    fun generate(): String = "sk-" + ByteArray(32).also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun matches(expected: String, supplied: String): Boolean = expected.isNotBlank() &&
        MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), supplied.toByteArray(Charsets.UTF_8))
}
