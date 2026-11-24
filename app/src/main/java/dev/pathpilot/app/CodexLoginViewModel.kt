package dev.pathpilot.app

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pathpilot.app.runtime.AppStore
import dev.pathpilot.core.codex.CodexAuthException
import dev.pathpilot.core.codex.CodexOAuthClient
import dev.pathpilot.core.codex.CodexTokens
import dev.pathpilot.core.codex.DeviceLoginChallenge
import dev.pathpilot.core.codex.DeviceLoginResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CodexLoginPhase { IDLE, REQUESTING, POLLING, SAVING, AUTH_ERROR, SAVE_ERROR, COMPLETE }

data class CodexLoginUiState(
    val providerId: String? = null,
    val phase: CodexLoginPhase = CodexLoginPhase.IDLE,
    val challenge: DeviceLoginChallenge? = null,
    val error: String? = null,
)

/** Activity-scoped login survives rotation, but credentials never enter saved-instance state. */
class CodexLoginViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(CodexLoginUiState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var store: AppStore? = null
    private var pendingTokens: CodexTokens? = null

    fun begin(providerId: String, appStore: AppStore) {
        if (mutableState.value.providerId == providerId) return
        cancel()
        store = appStore
        mutableState.value = CodexLoginUiState(providerId, CodexLoginPhase.REQUESTING)
        startAuthorization()
    }

    fun retryAuthorization() {
        if (mutableState.value.phase == CodexLoginPhase.AUTH_ERROR) startAuthorization()
    }

    fun retrySave() {
        if (mutableState.value.phase != CodexLoginPhase.SAVE_ERROR || pendingTokens == null) return
        job = viewModelScope.launch { savePending(retry = true) }
    }

    fun browserUnavailable() {
        if (mutableState.value.phase != CodexLoginPhase.POLLING) return
        job?.cancel()
        mutableState.value = mutableState.value.copy(
            phase = CodexLoginPhase.AUTH_ERROR,
            error = "未找到浏览器，请安装浏览器后重新授权。",
        )
    }

    fun cancel() {
        job?.cancel()
        job = null
        pendingTokens = null
        store = null
        mutableState.value = CodexLoginUiState()
    }

    private fun startAuthorization() {
        job?.cancel()
        val providerId = mutableState.value.providerId ?: return
        val appStore = store ?: return
        mutableState.value = CodexLoginUiState(providerId, CodexLoginPhase.REQUESTING)
        job = viewModelScope.launch {
            try {
                val client = CodexOAuthClient(proxyProvider = { appStore.config.value.outboundProxy })
                val challenge = withContext(Dispatchers.IO) { client.beginDeviceLogin() }
                mutableState.value = CodexLoginUiState(providerId, CodexLoginPhase.POLLING, challenge)
                while (isActive) {
                    delay(challenge.intervalSeconds.coerceAtLeast(1) * 1000)
                    when (val result = withContext(Dispatchers.IO) { client.pollDeviceLogin(challenge) }) {
                        DeviceLoginResult.Pending -> Unit
                        is DeviceLoginResult.Authorized -> {
                            pendingTokens = result.tokens
                            savePending()
                            break
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                Log.e("PathPilotLogin", "Authorization failed: ${error.javaClass.simpleName}")
                mutableState.value = mutableState.value.copy(
                    phase = CodexLoginPhase.AUTH_ERROR,
                    error = if (error is CodexAuthException) error.message
                        else "授权失败，请重试。",
                )
            }
        }
    }

    private suspend fun savePending(retry: Boolean = false) {
        val tokens = pendingTokens ?: return
        val providerId = mutableState.value.providerId ?: return
        val appStore = store ?: return
        mutableState.value = mutableState.value.copy(phase = CodexLoginPhase.SAVING, error = null)
        try {
            withContext(Dispatchers.IO) {
                if (retry) appStore.retryPendingCodexSave(providerId)
                else appStore.saveCodexTokens(providerId, tokens)
            }
            pendingTokens = null
            mutableState.value = mutableState.value.copy(phase = CodexLoginPhase.COMPLETE, challenge = null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            Log.e("PathPilotLogin", "Credential save failed: ${error.javaClass.simpleName}")
            mutableState.value = mutableState.value.copy(
                phase = CodexLoginPhase.SAVE_ERROR,
                error = "授权已完成，但保存失败。请重试保存。",
            )
        }
    }

    override fun onCleared() {
        pendingTokens = null
        store = null
        super.onCleared()
    }
}
