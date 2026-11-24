package dev.pathpilot.core.providers

import dev.pathpilot.core.ProviderKind
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class ProviderProbeResult(val models: List<String>) {
    override fun toString() = "ProviderProbeResult(modelCount=${models.size})"
}

enum class ProviderProbeError {
    MODELS_UNSUPPORTED, UNAUTHORIZED, FORBIDDEN, RATE_LIMITED, TIMEOUT, NETWORK,
    INVALID_RESPONSE, RESPONSE_TOO_LARGE, INVALID_CONFIGURATION, CANCELLED, UPSTREAM,
}

class ProviderProbeException(val kind: ProviderProbeError, val userMessage: String) : IOException(userMessage)

/** Only lists models; never sends a billable generation request. A cancelled instance is not reusable. */
class ProviderModelsClient internal constructor(client: OkHttpClient, private val allowLocalHttpTests: Boolean) {
    constructor(client: OkHttpClient = defaultClient()) : this(client, false)

    private val transport = client.newBuilder()
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .callTimeout(capTimeout(client.callTimeoutMillis, 10_000), TimeUnit.MILLISECONDS)
        .connectTimeout(capTimeout(client.connectTimeoutMillis, 15_000), TimeUnit.MILLISECONDS)
        .readTimeout(capTimeout(client.readTimeoutMillis, 15_000), TimeUnit.MILLISECONDS)
        .writeTimeout(capTimeout(client.writeTimeoutMillis, 15_000), TimeUnit.MILLISECONDS)
        .build()
    private val cancelled = AtomicBoolean(false)
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val json = Json { ignoreUnknownKeys = true }

    fun probe(baseUrl: String, apiKey: String): ProviderProbeResult {
        if (cancelled.get()) throw failure(ProviderProbeError.CANCELLED)
        val validation = ProviderSetup.validateInternal("provider", baseUrl, apiKey, ProviderKind.OPENAI_COMPATIBLE, allowLocalHttpTests)
        if (!validation.isValid) throw ProviderProbeException(ProviderProbeError.INVALID_CONFIGURATION,
            validation.urlError ?: validation.keyError ?: "请检查供应商配置")
        val request = Request.Builder().url(validation.normalizedBaseUrl + "/models")
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Accept", "application/json").get().build()
        val call = transport.newCall(request)
        calls.add(call)
        try {
            if (cancelled.get()) { call.cancel(); throw failure(ProviderProbeError.CANCELLED) }
            call.execute().use { response ->
                when (response.code) {
                    401 -> throw failure(ProviderProbeError.UNAUTHORIZED)
                    403 -> throw failure(ProviderProbeError.FORBIDDEN)
                    404, 405 -> throw failure(ProviderProbeError.MODELS_UNSUPPORTED)
                    429 -> throw failure(ProviderProbeError.RATE_LIMITED)
                    in 200..299 -> Unit
                    else -> throw failure(ProviderProbeError.UPSTREAM)
                }
                val body = response.body ?: throw failure(ProviderProbeError.INVALID_RESPONSE)
                if (body.contentLength() > MAX_RESPONSE_BYTES) throw failure(ProviderProbeError.RESPONSE_TOO_LARGE)
                val bytes = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        if (bytes.size() + count > MAX_RESPONSE_BYTES) throw failure(ProviderProbeError.RESPONSE_TOO_LARGE)
                        bytes.write(buffer, 0, count)
                    }
                }
                if (cancelled.get()) throw failure(ProviderProbeError.CANCELLED)
                val root = try { json.parseToJsonElement(bytes.toString(Charsets.UTF_8.name())) as? JsonObject }
                    catch (_: Exception) { throw failure(ProviderProbeError.INVALID_RESPONSE) }
                val data = root?.get("data") as? JsonArray ?: throw failure(ProviderProbeError.INVALID_RESPONSE)
                val models = sortedSetOf<String>()
                for (entry in data) {
                    val id = (entry as? JsonObject)?.get("id") as? JsonPrimitive
                    if (id == null || !id.isString || id.content != id.content.trim() || ProviderSetup.validateModelId(id.content) != null)
                        throw failure(ProviderProbeError.INVALID_RESPONSE)
                    models.add(id.content)
                    if (models.size > ProviderSetup.MAX_MODELS) throw failure(ProviderProbeError.RESPONSE_TOO_LARGE)
                }
                return ProviderProbeResult(models.toList())
            }
        } catch (error: ProviderProbeException) {
            throw error
        } catch (_: InterruptedIOException) {
            // OkHttp also marks calls cancelled when its call-timeout expires.
            throw failure(if (cancelled.get()) ProviderProbeError.CANCELLED else ProviderProbeError.TIMEOUT)
        } catch (_: IOException) {
            throw failure(if (cancelled.get() || call.isCanceled()) ProviderProbeError.CANCELLED else ProviderProbeError.NETWORK)
        } catch (_: Exception) {
            throw failure(ProviderProbeError.INVALID_RESPONSE)
        } finally { calls.remove(call) }
    }

    /** Cancels both an active probe and a probe that has not started on its IO thread yet. */
    fun cancel() {
        cancelled.set(true)
        calls.forEach(Call::cancel)
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 1024 * 1024
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS).connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()

        private fun capTimeout(value: Int, maximum: Int): Long = if (value in 1..maximum) value.toLong() else maximum.toLong()
        private fun failure(kind: ProviderProbeError): ProviderProbeException = ProviderProbeException(kind, when (kind) {
            ProviderProbeError.MODELS_UNSUPPORTED -> "该供应商未提供模型列表接口，仍可手动填写模型 ID 并保存"
            ProviderProbeError.UNAUTHORIZED -> "认证失败，请检查 API Key 是否正确或已失效"
            ProviderProbeError.FORBIDDEN -> "当前凭据没有读取模型列表的权限，请检查账号权限；也可手动填写模型 ID"
            ProviderProbeError.RATE_LIMITED -> "供应商暂时限制请求频率，请稍后重试；也可手动填写模型 ID"
            ProviderProbeError.TIMEOUT -> "连接测试超时，请检查网络或稍后重试"
            ProviderProbeError.NETWORK -> "无法连接供应商，请检查网络、API 地址和 HTTPS 证书"
            ProviderProbeError.INVALID_RESPONSE -> "供应商返回的模型列表格式不受支持，请手动填写模型 ID"
            ProviderProbeError.RESPONSE_TOO_LARGE -> "模型列表超过 1 MiB 或 500 个模型，请手动填写所需模型 ID"
            ProviderProbeError.INVALID_CONFIGURATION -> "请检查 API 地址和 API Key"
            ProviderProbeError.CANCELLED -> "已取消连接测试"
            ProviderProbeError.UPSTREAM -> "供应商未能返回模型列表，请检查 API 地址或稍后重试；也可手动填写模型 ID"
        })
    }
}
