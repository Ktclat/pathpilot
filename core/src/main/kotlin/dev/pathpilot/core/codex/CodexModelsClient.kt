package dev.pathpilot.core.codex

import dev.pathpilot.core.network.OutboundHttpClientFactory
import dev.pathpilot.core.network.OutboundProxyConfig
import dev.pathpilot.core.providers.ProviderModelsClient
import dev.pathpilot.core.providers.ProviderProbeError
import dev.pathpilot.core.providers.ProviderProbeException
import dev.pathpilot.core.providers.ProviderProbeResult
import dev.pathpilot.core.providers.ProviderSetup
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lists the signed-in account's Codex catalog, without generating tokens or using /v1/models.
 * Methods block; the caller resolves/refreshes credentials on IO and owns encrypted persistence.
 * A cancelled instance is not reusable. The production endpoint cannot be supplied by a provider.
 */
class CodexModelsClient internal constructor(
    client: OkHttpClient,
    proxyProvider: () -> OutboundProxyConfig,
    private val endpoint: HttpUrl,
) {
    constructor(
        client: OkHttpClient = defaultClient(),
        proxyProvider: () -> OutboundProxyConfig = { OutboundProxyConfig() },
    ) : this(client, proxyProvider, "$MODELS_URL?client_version=$CLIENT_VERSION".toHttpUrl())

    private val transport = OutboundHttpClientFactory(client.newBuilder()
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .callTimeout(capTimeout(client.callTimeoutMillis, 10_000), TimeUnit.MILLISECONDS)
        .connectTimeout(capTimeout(client.connectTimeoutMillis, 15_000), TimeUnit.MILLISECONDS)
        .readTimeout(capTimeout(client.readTimeoutMillis, 15_000), TimeUnit.MILLISECONDS)
        .writeTimeout(capTimeout(client.writeTimeoutMillis, 15_000), TimeUnit.MILLISECONDS)
        .build(), proxyProvider)
    private val cancelled = AtomicBoolean(false)
    private val calls = ConcurrentHashMap.newKeySet<Call>()

    fun probe(accessToken: String, accountId: String): ProviderProbeResult {
        if (cancelled.get()) throw failure(ProviderProbeError.CANCELLED)
        if (!validCredential(accessToken) || !validCredential(accountId)) {
            throw failure(ProviderProbeError.INVALID_CONFIGURATION)
        }
        val request = Request.Builder().url(endpoint)
            .header("Authorization", "Bearer $accessToken")
            .header("ChatGPT-Account-ID", accountId)
            .header("originator", "codex_cli_rs")
            .header("User-Agent", "PathPilot/0.1 Android")
            .header("Accept", "application/json")
            .get().build()
        val call = try { transport.client().newCall(request) } catch (_: IllegalArgumentException) {
            throw ProviderProbeException(ProviderProbeError.INVALID_CONFIGURATION, "代理配置无效，请检查代理主机和端口")
        }
        calls.add(call)
        try {
            if (cancelled.get()) { call.cancel(); throw failure(ProviderProbeError.CANCELLED) }
            call.execute().use { response ->
                when (response.code) {
                    401 -> throw failure(ProviderProbeError.UNAUTHORIZED)
                    403 -> throw failure(ProviderProbeError.FORBIDDEN)
                    404, 405 -> throw failure(ProviderProbeError.MODELS_UNSUPPORTED)
                    407 -> throw ProviderProbeException(ProviderProbeError.UPSTREAM, "代理需要认证，当前仅支持无认证代理")
                    429 -> throw failure(ProviderProbeError.RATE_LIMITED)
                    in 200..299 -> Unit
                    else -> throw failure(ProviderProbeError.UPSTREAM)
                }
                val body = response.body ?: throw failure(ProviderProbeError.INVALID_RESPONSE)
                if (body.contentLength() > MAX_RESPONSE_BYTES) throw failure(ProviderProbeError.RESPONSE_TOO_LARGE)
                val source = body.source()
                source.request(MAX_RESPONSE_BYTES.toLong() + 1)
                if (source.buffer.size > MAX_RESPONSE_BYTES) throw failure(ProviderProbeError.RESPONSE_TOO_LARGE)
                if (cancelled.get()) throw failure(ProviderProbeError.CANCELLED)
                val root = try { Json.parseToJsonElement(source.readUtf8()) as? JsonObject }
                    catch (_: SerializationException) { throw failure(ProviderProbeError.INVALID_RESPONSE) }
                val catalog = root?.get("models") as? JsonArray ?: throw failure(ProviderProbeError.INVALID_RESPONSE)
                val allSlugs = hashSetOf<String>()
                val visible = linkedSetOf<String>()
                for (entry in catalog) {
                    val model = entry as? JsonObject ?: throw failure(ProviderProbeError.INVALID_RESPONSE)
                    val slug = model["slug"] as? JsonPrimitive
                    val visibility = model["visibility"] as? JsonPrimitive
                    if (slug == null || !slug.isString || slug.content != slug.content.trim() ||
                        ProviderSetup.validateModelId(slug.content) != null ||
                        visibility == null || !visibility.isString || visibility.content !in VISIBILITY_VALUES) {
                        throw failure(ProviderProbeError.INVALID_RESPONSE)
                    }
                    allSlugs.add(slug.content)
                    if (allSlugs.size > ProviderSetup.MAX_MODELS) throw failure(ProviderProbeError.RESPONSE_TOO_LARGE)
                    // supported_in_api filters API-key auth, not a ChatGPT subscription catalog.
                    if (visibility.content == "list") visible.add(slug.content)
                }
                return ProviderProbeResult(visible.toList())
            }
        } catch (error: ProviderProbeException) {
            throw error
        } catch (_: InterruptedIOException) {
            throw failure(if (cancelled.get()) ProviderProbeError.CANCELLED else ProviderProbeError.TIMEOUT)
        } catch (_: IOException) {
            throw failure(if (cancelled.get() || call.isCanceled()) ProviderProbeError.CANCELLED else ProviderProbeError.NETWORK)
        } finally { calls.remove(call) }
    }

    fun cancel() {
        cancelled.set(true)
        calls.forEach(Call::cancel)
    }

    companion object {
        const val MODELS_URL = "${CodexBackend.BASE_URL}/models"
        // Catalog compatibility version pinned to official rust-v0.172.0, not the PathPilot version.
        const val CLIENT_VERSION = "0.172.0"
        // The pinned 14-model catalog is already 667,893 bytes, mostly nested instructions.
        // Give this richer schema its own bound instead of the generic /models 1 MiB limit.
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        private val VISIBILITY_VALUES = setOf("list", "hide", "none")

        fun defaultClient(): OkHttpClient = ProviderModelsClient.defaultClient()
        private fun validCredential(value: String): Boolean = value.isNotEmpty() && value.all { it.code in 33..126 }
        private fun capTimeout(value: Int, maximum: Int): Long = if (value in 1..maximum) value.toLong() else maximum.toLong()
        private fun failure(kind: ProviderProbeError): ProviderProbeException = ProviderProbeException(kind, when (kind) {
            ProviderProbeError.MODELS_UNSUPPORTED -> "Codex 未提供模型列表，可手动填写模型"
            ProviderProbeError.UNAUTHORIZED -> "Codex 登录已过期，请重新登录"
            ProviderProbeError.FORBIDDEN -> "当前 Codex 账号无权读取模型列表"
            ProviderProbeError.RATE_LIMITED -> "请求过于频繁，请稍后重试"
            ProviderProbeError.TIMEOUT -> "获取 Codex 模型超时"
            ProviderProbeError.NETWORK -> "无法连接 Codex，请检查网络或代理"
            ProviderProbeError.INVALID_RESPONSE -> "无法识别 Codex 模型列表格式"
            ProviderProbeError.RESPONSE_TOO_LARGE -> "Codex 模型列表超过 4 MiB 或 500 个模型"
            ProviderProbeError.INVALID_CONFIGURATION -> "Codex 登录凭据无效，请重新登录"
            ProviderProbeError.CANCELLED -> "已取消获取模型"
            ProviderProbeError.UPSTREAM -> "Codex 未能返回模型列表"
        })
    }
}
