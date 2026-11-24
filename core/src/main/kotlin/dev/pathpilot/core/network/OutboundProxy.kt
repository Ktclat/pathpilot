package dev.pathpilot.core.network

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.io.IOException

@Serializable
enum class OutboundProxyMode { SYSTEM, HTTP, SOCKS5 }

@Serializable
data class OutboundProxyConfig(
    val mode: OutboundProxyMode = OutboundProxyMode.SYSTEM,
    val host: String = "",
    val port: Int = 7890,
)

data class ProxyValidation(val hostError: String? = null, val portError: String? = null) {
    val isValid: Boolean get() = hostError == null && portError == null
}

object OutboundProxyValidation {
    fun validate(config: OutboundProxyConfig): ProxyValidation {
        if (config.mode == OutboundProxyMode.SYSTEM) return ProxyValidation()
        val host = config.host.trim()
        val hostError = when {
            host.isEmpty() -> "请输入代理主机，例如 127.0.0.1"
            config.host.any { Character.isISOControl(it) } || host.any(Char::isWhitespace) -> "代理主机不能包含空白或控制字符"
            host.length > 253 -> "代理主机地址过长"
            host.any { it in "/\\@?#" } -> "只填写主机名或 IP，不要填写协议、端口或路径"
            canonicalHost(host) == null -> "请输入有效的主机名或 IP；端口请单独填写"
            else -> null
        }
        return ProxyValidation(hostError, if (config.port in 1..65535) null else "代理端口必须在 1–65535 之间")
    }

    fun normalized(config: OutboundProxyConfig): OutboundProxyConfig {
        val result = validate(config)
        require(result.isValid) { result.hostError ?: result.portError ?: "代理配置无效" }
        return if (config.mode == OutboundProxyMode.SYSTEM) OutboundProxyConfig()
        else config.copy(host = requireNotNull(canonicalHost(config.host.trim())))
    }

    private fun canonicalHost(host: String): String? {
        val authority = if (':' in host && !host.startsWith('[')) "[$host]" else host
        val url = "http://$authority:7890".toHttpUrlOrNull() ?: return null
        return url.host.takeIf { url.encodedPath == "/" && url.username.isEmpty() && url.password.isEmpty() }
    }
}

/**
 * Applies one application proxy policy without binding sockets to an Android Network.
 * SYSTEM uses the platform ProxySelector and normal sockets, so Android VPN routing remains
 * in control. Explicit HTTP/SOCKS proxies also use normal sockets for their proxy connection.
 * SOCKS destinations remain unresolved for remote DNS. No proxy credentials are accepted.
 */
class OutboundHttpClientFactory(
    private val baseClient: OkHttpClient = OkHttpClient(),
    private val proxyProvider: () -> OutboundProxyConfig = { OutboundProxyConfig() },
    private val systemProxySelector: ProxySelector? = null,
) {
    private var cachedConfig: OutboundProxyConfig? = null
    private var cachedClient: OkHttpClient? = null
    private var cachedSystemSelector: ProxySelector? = null

    @Synchronized
    fun client(config: OutboundProxyConfig = proxyProvider()): OkHttpClient {
        val normalized = OutboundProxyValidation.normalized(config)
        val selector = systemProxySelector ?: ProxySelector.getDefault() ?: PlatformDirectSelector
        if (normalized == cachedConfig && (normalized.mode != OutboundProxyMode.SYSTEM || selector === cachedSystemSelector))
            return requireNotNull(cachedClient)
        val builder = baseClient.newBuilder()
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        when (normalized.mode) {
            OutboundProxyMode.SYSTEM -> builder.proxy(null).proxySelector(selector)
            OutboundProxyMode.HTTP -> builder.proxy(Proxy(Proxy.Type.HTTP,
                InetSocketAddress.createUnresolved(normalized.host, normalized.port)))
            OutboundProxyMode.SOCKS5 -> builder.proxy(Proxy(Proxy.Type.SOCKS,
                InetSocketAddress.createUnresolved(normalized.host, normalized.port)))
        }
        return builder.build().also { cachedConfig = normalized; cachedClient = it; cachedSystemSelector = selector }
    }

    /** Only used where the platform has no ProxySelector; it does not bypass VPN routing. */
    private object PlatformDirectSelector : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = listOf(Proxy.NO_PROXY)
        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
    }
}
