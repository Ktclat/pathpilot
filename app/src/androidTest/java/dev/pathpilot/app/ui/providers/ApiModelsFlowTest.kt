package dev.pathpilot.app.ui.providers

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pathpilot.app.MainActivity
import dev.pathpilot.app.runtime.AppStore
import dev.pathpilot.app.settings.BackupSnapshot
import dev.pathpilot.app.ui.settings.captureSettingsScreenshot
import dev.pathpilot.app.ui.settings.captureStore
import dev.pathpilot.app.ui.settings.prepareChineseCapture
import dev.pathpilot.core.ProviderConfig
import dev.pathpilot.core.ProviderKind
import dev.pathpilot.core.network.OutboundProxyConfig
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** MainActivity opens an API provider catalog directly from its model-management page. */
@RunWith(AndroidJUnit4::class)
class ApiModelsFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var store: AppStore
    private lateinit var original: BackupSnapshot
    private lateinit var server: MockWebServer
    private lateinit var provider: ProviderConfig
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun prepare() {
        store = captureStore()
        original = store.backupSnapshot(includeCodexSessions = true)
        prepareChineseCapture(store)
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        provider = ProviderConfig("offline-api", "兼容 API · 离线测试", ProviderKind.OPENAI_COMPATIBLE,
            server.url("/v1").toString(), apiKey = "offline-api-key")
        store.setConfig(store.config.value.copy(providers = listOf(provider), routes = emptyList(),
            outboundProxy = OutboundProxyConfig()))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("模型") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun clean() {
        try {
            scenario?.close()
        } finally {
            if (::server.isInitialized) server.shutdown()
            if (::original.isInitialized) store.restoreBackup(original, restoreConnectionSettings = true)
        }
    }

    @Test fun providerFetchOpensCatalogWithoutASecondClickAndSavesItsModel() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"data":[{"id":"offline-api-model"},{"id":"offline-api-reasoner"}]}"""))
        compose.onNode(hasText("模型") and hasClickAction()).performClick()
        compose.onNodeWithText(provider.name).performClick()
        compose.onNodeWithText("获取模型").assertIsDisplayed()
        compose.captureSettingsScreenshot("23-api-provider-models")
        compose.onNodeWithText("获取模型").performClick()

        compose.waitUntil(60_000) { compose.onAllNodes(hasText("已获取 2 个模型")).fetchSemanticsNodes().isNotEmpty() }
        val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("GET", request.method)
        assertEquals("/v1/models", request.path)
        assertEquals("Bearer offline-api-key", request.getHeader("Authorization"))
        assertEquals(1, server.requestCount)
        compose.onNodeWithText("offline-api-model").performScrollTo().performClick().assertIsOn()
        compose.captureSettingsScreenshot("24-api-model-selection")
        compose.onNodeWithText("保存").performClick()

        compose.waitUntil(30_000) { store.config.value.routes.size == 1 }
        val route = store.config.value.routes.single()
        assertEquals(provider.id, route.providerId)
        assertEquals("offline-api-model", route.upstreamModel)
        compose.onNodeWithContentDescription("返回供应商列表").assertIsDisplayed()
        compose.onNodeWithText("1 个模型 · 已启用 1 个").assertIsDisplayed()
        compose.captureSettingsScreenshot("25-api-model-saved")
        assertEquals(1, server.requestCount)
    }
}
