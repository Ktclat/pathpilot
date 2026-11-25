package dev.pathpilot.app.ui.providers

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathpilot.app.ProvidersScreen
import dev.pathpilot.app.ui.theme.PathPilotTheme
import dev.pathpilot.core.CodexCredentials
import dev.pathpilot.core.ProviderConfig
import dev.pathpilot.core.ProviderKind
import dev.pathpilot.core.RouterConfig
import dev.pathpilot.core.codex.CodexBackend
import dev.pathpilot.core.codex.CodexModelsClient
import dev.pathpilot.core.network.OutboundProxyConfig
import dev.pathpilot.core.providers.ProviderProbeResult
import dev.pathpilot.core.providers.ProviderRoutes
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Offline UI integration: production composables, ViewModel, catalog parser and route mutation.
 * Only the account resolver and upstream HTTP endpoint are fixtures; no real account is contacted.
 */
@RunWith(AndroidJUnit4::class)
class CodexModelsFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var server: MockWebServer
    private lateinit var transport: OkHttpClient
    private lateinit var model: ProviderEditorViewModel
    private val resolvedCredentials = AtomicInteger()
    private val originalUrl = AtomicReference<String>()
    private val savedConfig = AtomicReference<RouterConfig>()
    private val loginRequests = AtomicInteger()
    private val autoFetchStarts = AtomicInteger()
    private val provider = ProviderConfig(
        id = "offline-codex-provider",
        name = "Codex 订阅 · 离线测试",
        kind = ProviderKind.CODEX,
        baseUrl = CodexBackend.BASE_URL,
        codexAccountId = "offline-account",
    )

    @Before fun prepareOfflineTransport() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        transport = OkHttpClient.Builder().addInterceptor { chain ->
            val original = chain.request()
            // Fail closed if production ever changes to an unexpected destination or endpoint.
            check(original.url.host == "chatgpt.com")
            check(original.url.encodedPath == "/backend-api/codex/models")
            originalUrl.set(original.url.toString())
            val loopback = server.url(original.url.encodedPath).newBuilder()
                .encodedQuery(original.url.encodedQuery).build()
            chain.proceed(original.newBuilder().url(loopback).build())
        }.build()
        model = ProviderEditorViewModel(
            codexFactory = { proxy: OutboundProxyConfig ->
                val client = CodexModelsClient(client = transport, proxyProvider = { proxy })
                object : CodexModelProbe {
                    override suspend fun probe(credentials: CodexCredentials): ProviderProbeResult =
                        withContext(Dispatchers.IO) { client.probe(credentials.accessToken, credentials.accountId) }
                    override fun cancel() = client.cancel()
                }
            },
            credentialDispatcher = Dispatchers.IO,
        )
    }

    @After fun closeOfflineTransport() {
        if (::model.isInitialized) compose.runOnIdle { model.clear() }
        if (::transport.isInitialized) {
            transport.dispatcher.cancelAll()
            transport.connectionPool.evictAll()
            transport.dispatcher.executorService.shutdown()
        }
        if (::server.isInitialized) server.shutdown()
    }

    @Test fun signedInProviderFetchesCatalogSelectsModelAndSavesRoute() {
        server.enqueue(catalog())
        mount(signedIn = true, showEditorInitially = false)
        compose.onNodeWithText("已登录").assertIsDisplayed()
        compose.onNodeWithText("获取模型").assertIsDisplayed()
        screenshot("01-provider-entry")

        // Exercise the actual provider-list action, including the editor's automatic first fetch.
        compose.onNodeWithText("获取模型").performClick()
        awaitModels()
        assertCatalogRequest()
        compose.onNodeWithText(MODEL_A).performScrollTo().performClick().assertIsOn()
        compose.onNodeWithText("已选 1 个模型").assertIsDisplayed()
        screenshot("02-model-selection")
        compose.onNodeWithText("保存").performClick()

        compose.waitUntil(60_000) { savedConfig.get() != null }
        val saved = savedConfig.get()
        assertEquals(provider.id, saved.providers.single().id)
        assertEquals("", saved.providers.single().apiKey)
        assertEquals(provider.id, saved.routes.single().providerId)
        assertEquals(MODEL_A, saved.routes.single().upstreamModel)
        compose.onNodeWithText("1 个模型").assertIsDisplayed()
        assertEquals(1, server.requestCount)
        assertEquals(1, resolvedCredentials.get())
        assertEquals(1, autoFetchStarts.get())
    }

    @Test fun expiredLoginShowsErrorAndCanRetryModelFetch() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        server.enqueue(catalog())
        mount(signedIn = true, showEditorInitially = true)
        compose.onNodeWithText("获取模型").performScrollTo().performClick()
        compose.waitUntil(60_000) { model.state.value.draft.probePhase == ProbePhase.FAILED }
        compose.onNodeWithText("Codex 登录已过期，请重新登录").performScrollTo().assertIsDisplayed()
        screenshot("03-auth-error")
        assertEquals(1, server.requestCount)

        compose.onNodeWithText("获取模型").performScrollTo().performClick()
        awaitModels()
        compose.onNodeWithText(MODEL_A).performScrollTo().assertIsDisplayed()
        assertEquals(2, server.requestCount)
        // Retry obtains credentials again, allowing the real resolver to refresh an expired session.
        assertEquals(2, resolvedCredentials.get())
        assertTrue(model.state.value.draft.probeError == null)
    }

    @Test fun signedOutProviderRequestsLoginWithoutCallingCatalog() {
        mount(signedIn = false, showEditorInitially = true)
        compose.onNodeWithText("未登录").assertIsDisplayed()
        compose.onNodeWithText("登录后可获取模型").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("获取模型").assertIsNotEnabled()
        compose.onNodeWithText("登录").performScrollTo().performClick()
        compose.waitUntil(60_000) { loginRequests.get() == 1 }
        assertEquals(0, resolvedCredentials.get())
        assertEquals(0, server.requestCount)
    }

    private fun mount(signedIn: Boolean, showEditorInitially: Boolean) {
        val initial = RouterConfig(accessKey = "offline-router-key", providers = listOf(provider))
        val resolver: ((ProviderConfig) -> CodexCredentials)? = if (signedIn) ({ requested ->
            check(requested.id == provider.id)
            check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            resolvedCredentials.incrementAndGet()
            CodexCredentials("offline-subscription-access-token", "offline-account")
        }) else null
        compose.setContent {
            PathPilotTheme {
                Surface(Modifier.fillMaxSize()) {
                    var config by remember { mutableStateOf(initial) }
                    var editing by remember { mutableStateOf(showEditorInitially) }
                    var fetchOnOpen by remember { mutableStateOf(false) }
                    if (editing) ProviderEditor(
                        existing = provider,
                        saving = false,
                        onDismiss = { editing = false },
                        onSave = { changed, selected ->
                            config = ProviderRoutes.addSelected(config, changed, selected)
                            savedConfig.set(config)
                            editing = false
                        },
                        onDelete = {},
                        codexSignedIn = signedIn,
                        resolveCodexCredentials = resolver,
                        fetchModelsOnOpen = fetchOnOpen,
                        onModelsFetchStarted = { autoFetchStarts.incrementAndGet(); fetchOnOpen = false },
                        onLogin = { loginRequests.incrementAndGet() },
                        model = model,
                    ) else ProvidersScreen(
                        config = config,
                        sessions = if (signedIn) setOf(provider.id) else emptySet(),
                        saving = false,
                        onAdd = {},
                        onEdit = { editing = true },
                        onToggle = { _, _ -> },
                        onLogin = { loginRequests.incrementAndGet() },
                        onRoute = {},
                        onModels = { fetchOnOpen = true; editing = true },
                        onManageModels = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun awaitModels() {
        compose.waitUntil(60_000) { model.state.value.active && model.state.value.draft.probePhase == ProbePhase.SUCCESS }
        compose.onNodeWithText("已获取 2 个模型").performScrollTo().assertIsDisplayed()
        assertEquals(listOf(MODEL_A, MODEL_B), model.state.value.draft.discoveredModels)
    }

    private fun assertCatalogRequest() {
        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("The catalog must arrive over an actual loopback HTTP connection", request)
        requireNotNull(request)
        assertEquals("GET", request.method)
        assertEquals("/backend-api/codex/models?client_version=${CodexModelsClient.CLIENT_VERSION}", request.path)
        assertEquals("Bearer offline-subscription-access-token", request.getHeader("Authorization"))
        assertEquals("offline-account", request.getHeader("ChatGPT-Account-ID"))
        assertEquals("${CodexModelsClient.MODELS_URL}?client_version=${CodexModelsClient.CLIENT_VERSION}", originalUrl.get())
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "codex-models-e2e")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()
    }

    private fun catalog() = MockResponse().setHeader("Content-Type", "application/json").setBody(
        """{"models":[
          {"slug":"$MODEL_A","visibility":"list","supported_in_api":false},
          {"slug":"$MODEL_B","visibility":"list","supported_in_api":false},
          {"slug":"hidden-offline-model","visibility":"hide","supported_in_api":true}
        ]}""".trimIndent(),
    )

    companion object {
        private const val MODEL_A = "gpt-5.4"
        private const val MODEL_B = "gpt-5.3-codex"
    }
}
