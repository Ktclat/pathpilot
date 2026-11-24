package dev.pathpilot.app.runtime

import dev.pathpilot.core.RequestEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RuntimeState(
    val running: Boolean = false,
    val port: Int = 8317,
    val addresses: List<LocalAddress> = emptyList(),
    val requestCount: Long = 0,
    val lastError: String? = null,
    val recentRequests: List<RequestEvent> = emptyList(),
    val starting: Boolean = false,
    val stopping: Boolean = false,
    val addressWarning: String? = null,
)

/** Process-local service observations. Never persisted: a restored snapshot is not a live server. */
object RouterRuntime {
    private val mutableState = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

    @Synchronized
    internal fun starting(): Boolean {
        val current = mutableState.value
        if (current.running || current.starting || current.stopping) return false
        mutableState.value = current.copy(starting = true, lastError = null)
        return true
    }

    @Synchronized
    internal fun started(port: Int, snapshot: LocalAddressSnapshot): Boolean {
        if (!mutableState.value.starting || mutableState.value.stopping) return false
        mutableState.value = RuntimeState(running = true, port = port, addresses = snapshot.addresses,
            addressWarning = snapshot.warning)
        return true
    }

    @Synchronized
    internal fun stopping(): Boolean {
        val current = mutableState.value
        if (current.stopping || (!current.running && !current.starting)) return false
        mutableState.value = current.copy(running = false, starting = false, stopping = true)
        return true
    }

    @Synchronized
    internal fun stopped(error: String? = null) {
        mutableState.value = mutableState.value.copy(running = false, starting = false, stopping = false, lastError = error)
    }

    @Synchronized
    internal fun addressesChanged(snapshot: LocalAddressSnapshot) {
        if (!mutableState.value.running) return
        mutableState.value = mutableState.value.copy(addresses = snapshot.addresses, addressWarning = snapshot.warning)
    }

    @Synchronized
    internal fun record(event: RequestEvent) {
        val current = mutableState.value
        if (!current.running) return
        mutableState.value = current.copy(
            requestCount = current.requestCount + 1,
            recentRequests = (listOf(event) + current.recentRequests).take(30),
        )
    }

    internal fun error(message: String) = stopped(message)
}
