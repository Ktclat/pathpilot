package dev.pathpilot.app

import android.util.Log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pathpilot.app.runtime.AppStore
import dev.pathpilot.core.codex.CodexAuthError
import dev.pathpilot.core.codex.CodexAuthException
import dev.pathpilot.core.codex.CodexTokens
import dev.pathpilot.core.codex.DeviceLoginChallenge
import dev.pathpilot.core.codex.DeviceLoginResult
import dev.pathpilot.core.network.OutboundProxyConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CodexLoginMode { BROWSER, DEVICE_CODE }
enum class CodexLoginPhase { IDLE, REQUESTING, BROWSER, POLLING, SAVING, AUTH_ERROR, SAVE_ERROR, COMPLETE }

data class CodexLoginUiState(
    val providerId: String? = null,
    val phase: CodexLoginPhase = CodexLoginPhase.IDLE,
    val mode: CodexLoginMode = CodexLoginMode.BROWSER,
    val challenge: DeviceLoginChallenge? = null,
    val browserUrl: String? = null,
    val error: Int? = null,
    val retrying: Boolean = false,
) {
    override fun toString() = "CodexLoginUiState(phase=$phase, mode=$mode, error=$error)"
}

internal interface CodexLoginAccount {
    val providerId: String
    val proxy: OutboundProxyConfig
    suspend fun save(tokens: CodexTokens)
    suspend fun retrySave()
}

internal interface CodexLoginSession {
    suspend fun beginBrowser(): String
    suspend fun awaitBrowserTokens(): CodexTokens
    suspend fun beginDevice(): DeviceLoginChallenge
    suspend fun pollDevice(challenge: DeviceLoginChallenge): DeviceLoginResult
    fun cancel()
}

/** Activity-scoped login survives rotation; credentials and PKCE stay in memory until encrypted save. */
class CodexLoginViewModel() : ViewModel() {
    private var sessionFactory: (OutboundProxyConfig) -> CodexLoginSession = { HttpCodexLoginSession(it) }
    private var reportFailure: (String) -> Unit = { Log.e("PathPilotLogin", it) }
    internal constructor(factory: (OutboundProxyConfig) -> CodexLoginSession) : this() {
        sessionFactory = factory
        reportFailure = {}
    }
    private val mutableState = MutableStateFlow(CodexLoginUiState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var account: CodexLoginAccount? = null
    private var session: CodexLoginSession? = null
    private var pendingTokens: CodexTokens? = null

    fun begin(providerId: String, store: AppStore) = begin(object : CodexLoginAccount {
        override val providerId = providerId
        override val proxy get() = store.config.value.outboundProxy
        override suspend fun save(tokens: CodexTokens) = withContext(Dispatchers.IO) { store.saveCodexTokens(providerId, tokens) }
        override suspend fun retrySave() = withContext(Dispatchers.IO) { store.retryPendingCodexSave(providerId) }
    })

    internal fun begin(account: CodexLoginAccount) {
        if (mutableState.value.providerId == account.providerId) return
        cancel()
        this.account = account
        mutableState.value = CodexLoginUiState(providerId = account.providerId)
        startAuthorization(CodexLoginMode.BROWSER)
    }

    fun useMode(mode: CodexLoginMode) {
        if (mutableState.value.phase in setOf(CodexLoginPhase.SAVING, CodexLoginPhase.SAVE_ERROR, CodexLoginPhase.COMPLETE)) return
        startAuthorization(mode)
    }

    fun retryAuthorization() {
        if (mutableState.value.phase == CodexLoginPhase.AUTH_ERROR) startAuthorization(mutableState.value.mode)
    }

    fun retrySave() {
        if (mutableState.value.phase != CodexLoginPhase.SAVE_ERROR || pendingTokens == null) return
        job = viewModelScope.launch { savePending(retry = true) }
    }

    fun browserUnavailable() {
        if (mutableState.value.phase !in setOf(CodexLoginPhase.BROWSER, CodexLoginPhase.POLLING)) return
        stopAuthorization()
        mutableState.value = mutableState.value.copy(phase = CodexLoginPhase.AUTH_ERROR, error = R.string.codex_browser_missing)
    }

    private fun stopAuthorization() {
        job?.cancel()
        job = null
        session?.cancel()
        session = null
    }

    fun cancel() {
        stopAuthorization()
        pendingTokens = null
        account = null
        mutableState.value = CodexLoginUiState()
    }

    private fun startAuthorization(mode: CodexLoginMode) {
        val currentAccount = account ?: return
        stopAuthorization()
        mutableState.value = CodexLoginUiState(currentAccount.providerId, CodexLoginPhase.REQUESTING, mode)
        val currentSession = sessionFactory(currentAccount.proxy)
        session = currentSession
        job = viewModelScope.launch {
            try {
                if (mode == CodexLoginMode.BROWSER) {
                    val url = currentSession.beginBrowser()
                    currentCoroutineContext().ensureActive()
                    mutableState.value = mutableState.value.copy(phase = CodexLoginPhase.BROWSER, browserUrl = url)
                    val tokens = currentSession.awaitBrowserTokens()
                    currentCoroutineContext().ensureActive()
                    pendingTokens = tokens
                    savePending()
                } else {
                    val challenge = currentSession.beginDevice()
                    currentCoroutineContext().ensureActive()
                    mutableState.value = mutableState.value.copy(phase = CodexLoginPhase.POLLING, challenge = challenge)
                    var interval = challenge.intervalSeconds.coerceAtLeast(1)
                    while (isActive) {
                        val remaining = (challenge.expiresAtEpochSeconds - System.currentTimeMillis() / 1000).coerceAtLeast(1)
                        delay(minOf(interval, remaining) * 1000)
                        val result = try {
                            currentSession.pollDevice(challenge)
                        } catch (error: CodexAuthException) {
                            if (!error.canRetryPolling) throw error
                            currentCoroutineContext().ensureActive()
                            mutableState.value = mutableState.value.copy(retrying = true)
                            // Keep the original device code. Only pre-exchange polling can be retried.
                            interval = (error.retryAfterSeconds ?: challenge.intervalSeconds.coerceAtLeast(5)).coerceIn(1, 900)
                            continue
                        }
                        currentCoroutineContext().ensureActive()
                        interval = challenge.intervalSeconds.coerceAtLeast(1)
                        mutableState.value = mutableState.value.copy(retrying = false)
                        if (result is DeviceLoginResult.Authorized) {
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
                reportFailure(if (error is CodexAuthException) "Authorization failed: ${error.stage}/${error.kind}, HTTP ${error.httpStatus}"
                    else "Authorization failed: ${error.javaClass.simpleName}")
                mutableState.value = mutableState.value.copy(
                    phase = CodexLoginPhase.AUTH_ERROR, error = loginErrorMessage(error), retrying = false,
                )
            } finally {
                currentSession.cancel()
                if (session === currentSession) session = null
            }
        }
    }

    private suspend fun savePending(retry: Boolean = false) {
        val tokens = pendingTokens ?: return
        val currentAccount = account ?: return
        mutableState.value = mutableState.value.copy(phase = CodexLoginPhase.SAVING, error = null)
        try {
            if (retry) currentAccount.retrySave() else currentAccount.save(tokens)
            currentCoroutineContext().ensureActive()
            pendingTokens = null
            mutableState.value = mutableState.value.copy(phase = CodexLoginPhase.COMPLETE, challenge = null, browserUrl = null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            reportFailure("Credential save failed: ${error.javaClass.simpleName}")
            mutableState.value = mutableState.value.copy(phase = CodexLoginPhase.SAVE_ERROR, error = R.string.ui_authorization_save_failed)
        }
    }

    override fun onCleared() { cancel(); super.onCleared() }
}

internal fun loginErrorMessage(error: Exception): Int = when (error) {
    is java.net.BindException -> R.string.codex_callback_busy
    is java.net.SocketTimeoutException -> R.string.codex_login_expired
    is CodexAuthException -> when (error.kind) {
        CodexAuthError.NETWORK -> R.string.codex_auth_network
        CodexAuthError.TIMEOUT -> R.string.codex_auth_timeout
        CodexAuthError.RATE_LIMITED -> R.string.codex_auth_rate_limit
        CodexAuthError.EXPIRED -> R.string.codex_login_expired
        CodexAuthError.DEVICE_LOGIN_UNAVAILABLE -> R.string.codex_device_unavailable
        CodexAuthError.UNAUTHORIZED -> R.string.codex_auth_rejected
        CodexAuthError.FORBIDDEN -> R.string.codex_auth_forbidden
        CodexAuthError.STATE_MISMATCH -> R.string.codex_callback_mismatch
        CodexAuthError.ACCESS_DENIED -> R.string.codex_auth_denied
        CodexAuthError.INVALID_RESPONSE -> R.string.codex_auth_invalid_response
        else -> R.string.ui_authorization_failed
    }
    else -> R.string.ui_authorization_failed
}
