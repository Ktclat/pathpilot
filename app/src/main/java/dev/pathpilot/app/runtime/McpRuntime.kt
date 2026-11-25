package dev.pathpilot.app.runtime

import dev.pathpilot.core.mcp.McpCallEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class McpRuntimeState(
    val running: Boolean = false,
    val starting: Boolean = false,
    val stopping: Boolean = false,
    val port: Int = 8318,
    val addresses: List<LocalAddress> = emptyList(),
    val addressWarning: String? = null,
    val error: String? = null,
    val recentCalls: List<McpCallEvent> = emptyList(),
)

/** Observations from the live MCP service, separate from model usage and persisted configuration. */
object McpRuntime {
    private val mutableState = MutableStateFlow(McpRuntimeState())
    val state: StateFlow<McpRuntimeState> = mutableState.asStateFlow()

    @Synchronized internal fun starting(): Boolean {
        val state = mutableState.value
        if (state.running || state.starting || state.stopping) return false
        mutableState.value = state.copy(starting = true, error = null)
        return true
    }

    @Synchronized internal fun started(port: Int, snapshot: LocalAddressSnapshot): Boolean {
        if (!mutableState.value.starting || mutableState.value.stopping) return false
        mutableState.value = McpRuntimeState(running = true, port = port, addresses = snapshot.addresses,
            addressWarning = snapshot.warning)
        return true
    }

    @Synchronized internal fun stopping(): Boolean {
        val state = mutableState.value
        if (state.stopping || (!state.running && !state.starting)) return false
        mutableState.value = state.copy(running = false, starting = false, stopping = true)
        return true
    }

    @Synchronized internal fun stopped(error: String? = null) {
        mutableState.value = mutableState.value.copy(running = false, starting = false, stopping = false, error = error)
    }

    @Synchronized internal fun addressesChanged(snapshot: LocalAddressSnapshot) {
        if (mutableState.value.running) mutableState.value = mutableState.value.copy(
            addresses = snapshot.addresses, addressWarning = snapshot.warning)
    }

    @Synchronized internal fun record(event: McpCallEvent) {
        if (!mutableState.value.running) return
        mutableState.value = mutableState.value.copy(recentCalls = (listOf(event) + mutableState.value.recentCalls).take(30))
    }
}
