package dev.pathpilot.app.ui.providers

import android.accessibilityservice.AccessibilityService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathpilot.app.CodexLoginAccount
import dev.pathpilot.app.CodexLoginDialog
import dev.pathpilot.app.CodexLoginPhase
import dev.pathpilot.app.CodexLoginSession
import dev.pathpilot.app.CodexLoginViewModel
import dev.pathpilot.app.runtime.AppStore
import dev.pathpilot.app.settings.BackupSnapshot
import dev.pathpilot.app.ui.settings.captureSettingsScreenshot
import dev.pathpilot.app.ui.settings.captureStore
import dev.pathpilot.app.ui.settings.prepareChineseCapture
import dev.pathpilot.app.ui.theme.PathPilotTheme
import dev.pathpilot.core.ProviderConfig
import dev.pathpilot.core.ProviderKind
import dev.pathpilot.core.codex.CodexAuthError
import dev.pathpilot.core.codex.CodexAuthException
import dev.pathpilot.core.codex.CodexAuthStage
import dev.pathpilot.core.codex.CodexBackend
import dev.pathpilot.core.codex.CodexTokens
import dev.pathpilot.core.codex.DeviceLoginChallenge
import dev.pathpilot.core.codex.DeviceLoginResult
import dev.pathpilot.core.network.OutboundProxyConfig
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production dialog and activity-scoped ViewModel; authorization responses are local fixtures. */
@RunWith(AndroidJUnit4::class)
class CodexLoginFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var store: AppStore
    private lateinit var original: BackupSnapshot
    private lateinit var server: MockWebServer
    private lateinit var model: CodexLoginViewModel
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private var sessions = emptyList<LoginSession>()
    private val sessionCreations = AtomicInteger()
    private val viewModelCreations = AtomicInteger()
    private val browserRequests = AtomicInteger()
    private val successCallbacks = AtomicInteger()
    private val dismissCallbacks = AtomicInteger()
    private val provider = ProviderConfig("offline-login-provider", "Codex", ProviderKind.CODEX, CodexBackend.BASE_URL)
    private val account = object : CodexLoginAccount {
        override val providerId = provider.id
        override val proxy = OutboundProxyConfig()
        override suspend fun save(tokens: CodexTokens) = error("This waiting-state fixture must not save tokens")
        override suspend fun retrySave() = error("This waiting-state fixture must not retry token storage")
    }

    @Before fun prepare() {
        store = captureStore()
        original = store.backupSnapshot(includeCodexSessions = true)
        prepareChineseCapture(store)
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/device") browserRequests.incrementAndGet()
                    return MockResponse().setHeader("Content-Type", "text/html; charset=utf-8")
                        .setBody("<!doctype html><meta name=viewport content='width=device-width'><title>PathPilot fixture</title><p>Local authorization fixture. Return to PathPilot to continue.</p>")
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
    }

    @After fun clean() {
        try {
            scenario?.close()
        } finally {
            try {
                if (::model.isInitialized) InstrumentationRegistry.getInstrumentation().runOnMainSync { model.cancel() }
                if (::server.isInitialized) server.shutdown()
            } finally {
                if (::original.isInitialized) store.restoreBackup(original, restoreConnectionSettings = true)
            }
        }
    }

    @Test fun deviceCodeAndNetworkRecoverySurviveActivityRecreation() {
        val browser = newSession()
        val device = newSession()
        launch(browser, device)
        awaitPhase(CodexLoginPhase.BROWSER)
        compose.onNodeWithText("在浏览器中登录 ChatGPT 账号，完成后返回 PathPilot。").assertIsDisplayed()
        compose.onNodeWithText("打开登录页面").assertIsDisplayed()
        capture("43-codex-browser-login")

        useDeviceCode()
        compose.onNodeWithText(device.challenge.userCode).assertIsDisplayed()
        capture("44-codex-device-waiting")
        val retainedModel = model
        lateinit var previousActivity: ComponentActivity
        requireNotNull(scenario).onActivity { previousActivity = it }
        requireNotNull(scenario).recreate()
        requireNotNull(scenario).onActivity { activity ->
            assertNotSame(previousActivity, activity)
            mount(activity)
        }
        awaitPhase(CodexLoginPhase.POLLING)
        assertSame(retainedModel, model)
        assertSame(device.challenge, model.state.value.challenge)
        compose.onNodeWithText(device.challenge.userCode).assertIsDisplayed()
        assertEquals(1, viewModelCreations.get())
        assertEquals(1, device.deviceBegins.get())
        assertEquals(0, device.cancellations.get())
        capture("45-codex-device-after-recreation")

        device.respond(Result.failure(CodexAuthException(
            httpStatus = null,
            message = "Offline connection interruption",
            kind = CodexAuthError.NETWORK,
            stage = CodexAuthStage.DEVICE_POLL,
            retryAfterSeconds = 1,
        )))
        compose.waitUntil(30_000) { model.state.value.retrying }
        compose.onNodeWithText("连接中断，正在使用原验证码重新连接…").assertIsDisplayed()
        compose.onNodeWithText(device.challenge.userCode).assertIsDisplayed()
        capture("46-codex-device-reconnecting")

        device.respond(Result.success(DeviceLoginResult.Pending))
        compose.waitUntil(30_000) { !model.state.value.retrying && device.polledChallenges.size >= 2 }
        compose.onNodeWithText("等待登录…").assertIsDisplayed()
        compose.onNodeWithText(device.challenge.userCode).assertIsDisplayed()
        assertTrue(device.polledChallenges.all { it === device.challenge })
        assertEquals(1, browser.browserBegins.get())
        assertEquals(1, device.deviceBegins.get())
        assertEquals(2, sessionCreations.get())
        assertEquals(0, successCallbacks.get())
        assertEquals(0, dismissCallbacks.get())
        capture("47-codex-device-recovered")
    }

    @Test fun expiredDeviceAuthorizationShowsReadableErrorAndCanRestart() {
        val expired = newSession()
        val fresh = newSession("TEST-NEW2")
        launch(newSession(), expired, fresh)
        awaitPhase(CodexLoginPhase.BROWSER)
        useDeviceCode()
        expired.respond(Result.failure(CodexAuthException(
            httpStatus = 403,
            message = "fixture raw authorization response",
            kind = CodexAuthError.EXPIRED,
            stage = CodexAuthStage.DEVICE_POLL,
        )))
        awaitPhase(CodexLoginPhase.AUTH_ERROR)
        compose.onNodeWithText("登录已超时，请重新登录。").assertIsDisplayed()
        compose.onNodeWithText("fixture raw authorization response").assertDoesNotExist()
        compose.onNodeWithText("重新授权").assertIsDisplayed()
        capture("48-codex-login-expired")

        compose.onNodeWithText("重新授权").performClick()
        awaitPhase(CodexLoginPhase.POLLING)
        compose.onNodeWithText(fresh.challenge.userCode).assertIsDisplayed()
        assertEquals(1, expired.deviceBegins.get())
        assertEquals(1, fresh.deviceBegins.get())
        assertEquals(3, sessionCreations.get())
        assertSame(fresh.challenge, model.state.value.challenge)
        capture("49-codex-login-restarted")
    }

    /** Requires an installed browser; the real ACTION_VIEW opens only the loopback fixture. */
    @Test fun browserRoundTripRetainsThePendingDeviceCode() {
        val device = newSession()
        launch(newSession(), device)
        awaitPhase(CodexLoginPhase.BROWSER)
        useDeviceCode()
        val retainedModel = model
        compose.onNodeWithText("打开登录页面").performClick()
        compose.waitUntil(30_000) {
            browserRequests.get() > 0 && requireNotNull(scenario).state == Lifecycle.State.CREATED
        }

        // Use Android's actual Back action after the browser has loaded the local page.
        assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation
            .performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        compose.waitUntil(30_000) { requireNotNull(scenario).state == Lifecycle.State.RESUMED }
        compose.onNodeWithText(device.challenge.userCode).assertIsDisplayed()
        compose.onNodeWithText("等待登录…").assertIsDisplayed()
        assertSame(retainedModel, model)
        assertSame(device.challenge, model.state.value.challenge)
        assertEquals(1, viewModelCreations.get())
        assertEquals(1, device.deviceBegins.get())
        assertEquals(2, sessionCreations.get())
        assertEquals(0, device.cancellations.get())
        capture("50-codex-device-after-browser")
    }

    private fun launch(vararg plannedSessions: LoginSession) {
        sessions = plannedSessions.toList()
        scenario = ActivityScenario.launch(ComponentActivity::class.java).also { launched ->
            launched.onActivity { mount(it) }
        }
    }

    private fun mount(activity: ComponentActivity) {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                check(modelClass == CodexLoginViewModel::class.java)
                viewModelCreations.incrementAndGet()
                return CodexLoginViewModel { sessions[sessionCreations.getAndIncrement()] } as T
            }
        }
        model = ViewModelProvider(activity, factory)[CodexLoginViewModel::class.java]
        model.begin(account)
        activity.setContent {
            PathPilotTheme(store.preferences.value) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    CodexLoginDialog(provider, store,
                        onDismiss = { dismissCallbacks.incrementAndGet() },
                        onSuccess = { successCallbacks.incrementAndGet() }, loginModel = model)
                }
            }
        }
    }

    private fun useDeviceCode() {
        compose.onNodeWithText("改用设备码登录").performClick()
        awaitPhase(CodexLoginPhase.POLLING)
    }

    private fun awaitPhase(phase: CodexLoginPhase) {
        compose.waitUntil(30_000) { model.state.value.phase == phase }
    }

    private fun capture(name: String) = compose.captureSettingsScreenshot(name, internalStorage = true)

    private fun newSession(code: String = "TEST-CODE") = LoginSession(
        server.url("/authorize").toString(),
        DeviceLoginChallenge(server.url("/device").toString(), code, "offline-device-$code", 1,
            System.currentTimeMillis() / 1000 + 600),
    )

    private class LoginSession(private val browserUrl: String, val challenge: DeviceLoginChallenge) : CodexLoginSession {
        val browserBegins = AtomicInteger()
        val deviceBegins = AtomicInteger()
        val cancellations = AtomicInteger()
        val polledChallenges = CopyOnWriteArrayList<DeviceLoginChallenge>()
        private val responses = Channel<Result<DeviceLoginResult>>(Channel.UNLIMITED)

        override suspend fun beginBrowser(): String { browserBegins.incrementAndGet(); return browserUrl }
        override suspend fun awaitBrowserTokens(): CodexTokens = awaitCancellation()
        override suspend fun beginDevice(): DeviceLoginChallenge { deviceBegins.incrementAndGet(); return challenge }
        override suspend fun pollDevice(challenge: DeviceLoginChallenge): DeviceLoginResult {
            polledChallenges += challenge
            return responses.receive().getOrThrow()
        }
        override fun cancel() { cancellations.incrementAndGet() }
        fun respond(result: Result<DeviceLoginResult>) { responses.trySend(result).getOrThrow() }
    }
}
