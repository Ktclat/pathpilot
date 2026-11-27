package dev.pathpilot.app

import dev.pathpilot.core.codex.CodexAuthError
import dev.pathpilot.core.codex.CodexAuthException
import dev.pathpilot.core.codex.CodexAuthStage
import dev.pathpilot.core.codex.CodexTokens
import dev.pathpilot.core.codex.DeviceLoginChallenge
import dev.pathpilot.core.codex.DeviceLoginResult
import dev.pathpilot.core.network.OutboundProxyConfig
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CodexLoginViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = mutableListOf<CodexLoginViewModel>()
    private val sessions = mutableListOf<FakeSession>()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }

    @After fun tearDown() {
        viewModels.forEach { it.cancel() }
        sessions.forEach { it.finishPendingBrowser() }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test fun `browser login is the default and saves authorized tokens`() = runTest(dispatcher) {
        val session = FakeSession()
        val account = FakeAccount()
        val login = login(session)

        login.begin(account)
        assertEquals(CodexLoginPhase.REQUESTING, login.state.value.phase)
        assertEquals(CodexLoginMode.BROWSER, login.state.value.mode)
        runCurrent()
        assertEquals(CodexLoginPhase.BROWSER, login.state.value.phase)
        assertEquals(session.browserUrl, login.state.value.browserUrl)
        assertEquals(1, session.browserBegins)
        assertEquals(1, session.browserWaits)
        assertEquals(0, session.deviceBegins)

        session.completeBrowser(tokens)
        runCurrent()
        assertEquals(listOf(tokens), account.saveAttempts)
        assertEquals(CodexLoginPhase.COMPLETE, login.state.value.phase)
        assertNull(login.state.value.browserUrl)
        assertNull(login.state.value.challenge)
        assertEquals(1, session.cancellations)
    }

    @Test fun `cancel stops browser session and a late callback cannot change the next account`() = runTest(dispatcher) {
        val oldSession = FakeSession()
        val currentSession = FakeSession()
        val oldAccount = FakeAccount("old-provider")
        val currentAccount = FakeAccount("current-provider")
        val login = login(oldSession, currentSession)
        login.begin(oldAccount)
        runCurrent()

        login.cancel()
        assertEquals(1, oldSession.cancellations)
        assertEquals(CodexLoginPhase.IDLE, login.state.value.phase)
        assertNull(login.state.value.providerId)
        login.begin(currentAccount)
        runCurrent()
        val currentState = login.state.value

        // A callback can arrive after its transport has been cancelled.
        oldSession.completeBrowser(tokens)
        runCurrent()
        assertEquals(currentState, login.state.value)
        assertTrue(oldAccount.saveAttempts.isEmpty())
        assertTrue(currentAccount.saveAttempts.isEmpty())
        assertEquals(0, currentSession.cancellations)

        currentSession.completeBrowser(tokens.copy(accountId = "current-account"))
        runCurrent()
        assertEquals("current-account", currentAccount.saveAttempts.single().accountId)
        assertEquals(CodexLoginPhase.COMPLETE, login.state.value.phase)
    }

    @Test fun `switching to device login stops browser and keeps the device challenge`() = runTest(dispatcher) {
        val browser = FakeSession()
        val device = FakeSession()
        val account = FakeAccount()
        val login = login(browser, device)
        login.begin(account)
        runCurrent()

        login.useMode(CodexLoginMode.DEVICE_CODE)
        assertEquals(1, browser.cancellations)
        runCurrent()
        assertEquals(CodexLoginMode.DEVICE_CODE, login.state.value.mode)
        assertEquals(CodexLoginPhase.POLLING, login.state.value.phase)
        assertSame(device.challenge, login.state.value.challenge)
        assertNull(login.state.value.browserUrl)
        assertEquals(1, device.deviceBegins)
        assertEquals(0, device.browserBegins)

        browser.completeBrowser(tokens)
        runCurrent()
        assertEquals(CodexLoginPhase.POLLING, login.state.value.phase)
        assertSame(device.challenge, login.state.value.challenge)
        assertTrue(account.saveAttempts.isEmpty())
        login.cancel()
        runCurrent()
    }

    @Test fun `transient device poll failure retries the same challenge without beginning again`() = runTest(dispatcher) {
        val browser = FakeSession()
        val device = FakeSession().apply {
            pollResult = {
                if (polledChallenges.size == 1) throw authFailure(CodexAuthStage.DEVICE_POLL)
                DeviceLoginResult.Authorized(tokens)
            }
        }
        val account = FakeAccount()
        val login = login(browser, device)
        login.begin(account)
        login.useMode(CodexLoginMode.DEVICE_CODE)
        runCurrent()

        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(login.state.value.retrying)
        assertEquals(CodexLoginPhase.POLLING, login.state.value.phase)
        assertSame(device.challenge, login.state.value.challenge)
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(1, device.deviceBegins)
        assertEquals(2, device.polledChallenges.size)
        assertTrue(device.polledChallenges.all { it === device.challenge })
        assertEquals(listOf(tokens), account.saveAttempts)
        assertEquals(CodexLoginPhase.COMPLETE, login.state.value.phase)
        assertFalse(login.state.value.retrying)
    }

    @Test fun `network failure exchanging a device code is not automatically retried`() = runTest(dispatcher) {
        val device = FakeSession().apply {
            pollResult = { throw authFailure(CodexAuthStage.CODE_EXCHANGE) }
        }
        val account = FakeAccount()
        val login = login(FakeSession(), device)
        login.begin(account)
        login.useMode(CodexLoginMode.DEVICE_CODE)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(CodexLoginPhase.AUTH_ERROR, login.state.value.phase)
        assertEquals(R.string.codex_auth_network, login.state.value.error)
        assertFalse(login.state.value.retrying)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, device.deviceBegins)
        assertEquals(1, device.polledChallenges.size)
        assertEquals(1, device.cancellations)
        assertTrue(account.saveAttempts.isEmpty())
    }

    @Test fun `device poll respects Retry-After before the next attempt`() = runTest(dispatcher) {
        val device = FakeSession().apply {
            pollResult = {
                if (polledChallenges.size == 1) throw CodexAuthException(
                    httpStatus = 429,
                    message = "Test rate limit",
                    kind = CodexAuthError.RATE_LIMITED,
                    stage = CodexAuthStage.DEVICE_POLL,
                    retryAfterSeconds = 12,
                )
                DeviceLoginResult.Authorized(tokens)
            }
        }
        val login = login(FakeSession(), device)
        login.begin(FakeAccount())
        login.useMode(CodexLoginMode.DEVICE_CODE)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, device.polledChallenges.size)
        assertTrue(login.state.value.retrying)

        advanceTimeBy(11_999)
        runCurrent()
        assertEquals(1, device.polledChallenges.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, device.polledChallenges.size)
        assertEquals(CodexLoginPhase.COMPLETE, login.state.value.phase)
    }

    @Test fun `failed credential save retries storage without another authorization`() = runTest(dispatcher) {
        val session = FakeSession()
        val account = FakeAccount().apply { saveFailure = IOException("Test storage failure") }
        val login = login(session)
        login.begin(account)
        runCurrent()
        session.completeBrowser(tokens)
        runCurrent()
        assertEquals(CodexLoginPhase.SAVE_ERROR, login.state.value.phase)
        assertEquals(R.string.ui_authorization_save_failed, login.state.value.error)
        assertEquals(listOf(tokens), account.saveAttempts)

        login.retrySave()
        runCurrent()
        assertEquals(1, account.retrySaves)
        assertEquals(1, account.saveAttempts.size)
        assertEquals(1, session.browserBegins)
        assertEquals(1, session.browserWaits)
        assertEquals(0, session.deviceBegins)
        assertEquals(CodexLoginPhase.COMPLETE, login.state.value.phase)
        assertNull(login.state.value.error)
    }

    @Test fun `begin for the same provider after activity recreation retains the session`() = runTest(dispatcher) {
        val session = FakeSession()
        val account = FakeAccount()
        val login = login(session)
        login.begin(account)
        runCurrent()
        val beforeRecreation = login.state.value

        login.begin(FakeAccount(account.providerId))
        runCurrent()
        assertEquals(beforeRecreation, login.state.value)
        assertEquals(1, session.browserBegins)
        assertEquals(1, session.browserWaits)
        assertEquals(0, session.cancellations)

        session.completeBrowser(tokens)
        runCurrent()
        assertEquals(listOf(tokens), account.saveAttempts)
        assertEquals(CodexLoginPhase.COMPLETE, login.state.value.phase)
    }

    private fun login(vararg availableSessions: FakeSession): CodexLoginViewModel {
        sessions += availableSessions
        var next = 0
        return CodexLoginViewModel { availableSessions[next++] }.also { viewModels += it }
    }

    private class FakeAccount(override val providerId: String = "test-provider") : CodexLoginAccount {
        override val proxy = OutboundProxyConfig()
        val saveAttempts = mutableListOf<CodexTokens>()
        var saveFailure: Exception? = null
        var retrySaves = 0

        override suspend fun save(tokens: CodexTokens) {
            saveAttempts += tokens
            saveFailure?.let { throw it }
        }

        override suspend fun retrySave() { retrySaves++ }
    }

    private class FakeSession : CodexLoginSession {
        val browserUrl = "https://auth.example.test/authorize"
        val challenge = DeviceLoginChallenge(
            verificationUrl = "https://auth.example.test/device",
            userCode = "TEST-CODE",
            deviceAuthId = "test-device",
            intervalSeconds = 2,
            expiresAtEpochSeconds = System.currentTimeMillis() / 1000 + 600,
        )
        var browserBegins = 0
        var browserWaits = 0
        var deviceBegins = 0
        var cancellations = 0
        val polledChallenges = mutableListOf<DeviceLoginChallenge>()
        var pollResult: () -> DeviceLoginResult = { DeviceLoginResult.Pending }
        private var browserContinuation: Continuation<CodexTokens>? = null

        override suspend fun beginBrowser(): String { browserBegins++; return browserUrl }

        override suspend fun awaitBrowserTokens(): CodexTokens {
            browserWaits++
            return suspendCoroutine { browserContinuation = it }
        }

        override suspend fun beginDevice(): DeviceLoginChallenge { deviceBegins++; return challenge }

        override suspend fun pollDevice(challenge: DeviceLoginChallenge): DeviceLoginResult {
            polledChallenges += challenge
            return pollResult()
        }

        override fun cancel() { cancellations++ }

        fun completeBrowser(tokens: CodexTokens) {
            val continuation = requireNotNull(browserContinuation)
            browserContinuation = null
            continuation.resume(tokens)
        }

        fun finishPendingBrowser() {
            val continuation = browserContinuation ?: return
            browserContinuation = null
            continuation.resumeWithException(CancellationException("Test finished"))
        }
    }

    private companion object {
        val tokens = CodexTokens(
            accessToken = "test-access-token",
            refreshToken = "test-refresh-token",
            accountId = "test-account",
            expiresAtEpochSeconds = 4_000_000_000,
        )

        fun authFailure(stage: CodexAuthStage) = CodexAuthException(
            httpStatus = null,
            message = "Test connection failure",
            kind = CodexAuthError.NETWORK,
            stage = stage,
        )
    }
}
