package dev.pathpilot.app.runtime

import dev.pathpilot.core.RequestEvent
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RuntimeState(
    val running: Boolean = false,
    val port: Int = 8317,
    val addresses: List<String> = emptyList(),
    val requestCount: Long = 0,
    val lastError: String? = null,
    val recentRequests: List<RequestEvent> = emptyList(),
)

/** Process-local service observations. Never persisted: a restored snapshot is not a live server. */
object RouterRuntime {
    private val mutableState = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

    @Synchronized
    internal fun started(port: Int) {
        mutableState.value = RuntimeState(running = true, port = port, addresses = LocalAddresses.ipv4())
    }

    @Synchronized
    internal fun stopped(error: String? = null) {
        mutableState.value = mutableState.value.copy(running = false, lastError = error)
    }

    @Synchronized
    internal fun addressesChanged(addresses: List<String>) {
        mutableState.value = mutableState.value.copy(addresses = addresses)
    }

    @Synchronized
    internal fun record(event: RequestEvent) {
        val current = mutableState.value
        mutableState.value = current.copy(
            requestCount = current.requestCount + 1,
            recentRequests = (listOf(event) + current.recentRequests).take(30),
        )
    }

    internal fun error(message: String) = stopped(message)
}

object LocalAddresses {
    /** Includes Wi-Fi, Ethernet and hotspot interfaces; excludes loopback, IPv6 and link-local. */
    fun ipv4(): List<String> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
            .sorted()
    } catch (_: Exception) {
        emptyList()
    }
}
