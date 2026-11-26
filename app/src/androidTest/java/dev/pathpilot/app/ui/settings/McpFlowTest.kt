package dev.pathpilot.app.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathpilot.app.MainActivity
import dev.pathpilot.app.runtime.AppStore
import dev.pathpilot.app.runtime.McpRuntime
import dev.pathpilot.app.runtime.McpService
import dev.pathpilot.app.runtime.RouterRuntime
import dev.pathpilot.app.runtime.RouterService
import dev.pathpilot.app.settings.BackupSnapshot
import dev.pathpilot.core.mcp.McpClient
import dev.pathpilot.core.mcp.McpConnection
import dev.pathpilot.core.mcp.McpException
import dev.pathpilot.core.mcp.McpSettings
import java.net.ServerSocket
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real MainActivity → MCP UI → Android service → local HTTP upstream, without an external account. */
@RunWith(AndroidJUnit4::class)
class McpFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var store: AppStore
    private lateinit var original: BackupSnapshot
    private var scenario: ActivityScenario<MainActivity>? = null
    private var fixture: OfflineMcpFixture? = null
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val http = OkHttpClient()

    @Before fun prepare() {
        store = captureStore()
        original = store.backupSnapshot(includeCodexSessions = true)
        prepareChineseCapture(store)
        val ports = ServerSocket(0).use { model -> ServerSocket(0).use { mcp -> model.localPort to mcp.localPort } }
        store.setConfig(store.config.value.copy(port = ports.first, providers = emptyList(), routes = emptyList()))
        store.setMcpSettings(McpSettings(port = ports.second, accessKey = "sk-" + "m".repeat(40)))
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        fixture = OfflineMcpFixture()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("MCP") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun clean() {
        McpService.stop(context)
        RouterService.stop(context)
        compose.waitUntil(30_000) {
            !McpRuntime.state.value.running && !McpRuntime.state.value.starting && !McpRuntime.state.value.stopping &&
                !RouterRuntime.state.value.running && !RouterRuntime.state.value.starting && !RouterRuntime.state.value.stopping
        }
        try {
            scenario?.close()
        } finally {
            fixture?.close()
            if (::original.isInitialized) store.restoreBackup(original, restoreConnectionSettings = true)
        }
    }

    @Test fun connectionToolsAndIndependentSharingReachAnActualHttpUpstream() {
        val upstream = requireNotNull(fixture)
        compose.onNode(hasText("MCP") and hasClickAction()).performClick().assertIsSelected()
        compose.onNodeWithContentDescription("返回").assertDoesNotExist()
        compose.captureSettingsScreenshot("09a-mcp-root")
        compose.onNodeWithContentDescription("添加连接").performClick()
        compose.onNodeWithText("名称").performTextReplacement("工具 · 离线测试")
        compose.onNodeWithText("MCP URL").performTextReplacement(upstream.url)
        compose.onNodeWithText("Bearer").performScrollTo().performClick()
        compose.onNodeWithText("Token").performScrollTo().performTextReplacement(upstream.token)
        hideCaptureKeyboard()
        compose.captureSettingsScreenshot("09-mcp-editor")
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.waitUntil(30_000) { store.mcp.value.connections.size == 1 }
        val connection = store.mcp.value.connections.single()
        assertEquals(upstream.url, connection.url)
        assertEquals(upstream.token, connection.bearerToken)
        compose.onNode(hasText("MCP") and hasClickAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNode(hasText("MCP") and hasClickAction()).assertIsSelected()
        compose.onNode(hasText("设置") and hasClickAction()).performClick().assertIsSelected()
        compose.onNodeWithText("主题").assertIsDisplayed()
        compose.onNode(hasText("MCP") and hasClickAction()).performClick().assertIsSelected()
        compose.onNode(hasText(connection.name) and hasClickAction()).performClick()
        compose.onNodeWithText(connection.name).assertIsDisplayed()
        compose.onNode(hasText("MCP") and hasClickAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription("MCP 共享").assertIsDisplayed()
        compose.captureSettingsScreenshot("10a-mcp-access")

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("获取工具"))
        compose.onNodeWithText("获取工具").performClick()
        compose.waitUntil(30_000) { upstream.methods.contains("tools/list") }
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("获取工具")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("共享工具 offline_echo"))
        compose.onNodeWithContentDescription("共享工具 offline_echo").performClick()
        compose.waitUntil(30_000) { store.mcp.value.connections.single().allowedTools == setOf("offline_status") }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("offline_status"))
        compose.captureSettingsScreenshot("10-mcp-tools")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNode(hasText("MCP") and hasClickAction()).assertIsSelected()
        compose.onNodeWithContentDescription("返回").assertDoesNotExist()

        RouterService.start(context)
        compose.waitUntil(30_000) { RouterRuntime.state.value.running || RouterRuntime.state.value.lastError != null }
        assertTrue(RouterRuntime.state.value.toString(), RouterRuntime.state.value.running)
        compose.onNodeWithContentDescription("MCP 共享").performClick()
        compose.waitUntil(30_000) { McpRuntime.state.value.running || McpRuntime.state.value.error != null }
        assertTrue(McpRuntime.state.value.toString(), McpRuntime.state.value.running)
        compose.onNodeWithText("已开启").assertIsDisplayed()
        compose.captureSettingsScreenshot("11-mcp-sharing")

        val settings = store.mcp.value
        val endpoint = "http://127.0.0.1:${settings.port}/mcp/${connection.id}"
        val downstream = McpConnection(name = "Offline client", url = endpoint, bearerToken = settings.accessKey)
        McpClient(downstream).use { client ->
            assertEquals("2025-11-25", client.connect().protocolVersion)
            assertEquals(listOf("offline_status"), client.listTools().map { it.name })
            val result = client.callTool("offline_status", buildJsonObject {})
            assertTrue(result.toString().contains("offline result: offline_status"))
            assertThrows(McpException::class.java) { client.callTool("offline_echo", buildJsonObject {}) }
        }
        assertEquals(listOf("offline_status"), upstream.calls.toList())
        assertTrue(upstream.methods.containsAll(listOf("initialize", "notifications/initialized", "tools/list", "tools/call")))
        compose.waitUntil(30_000) { McpRuntime.state.value.recentCalls.any { it.toolName == "offline_status" && it.success } }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("最近调用"))
        compose.captureSettingsScreenshot("12-mcp-calls")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("offline_status"))
        compose.captureSettingsScreenshot("12a-mcp-success-call")

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("MCP 共享"))
        compose.onNodeWithContentDescription("MCP 共享").performClick()
        compose.waitUntil(30_000) { !McpRuntime.state.value.running && !McpRuntime.state.value.stopping }
        assertNull(McpRuntime.state.value.error)
        assertTrue("Model sharing continues after MCP is stopped", RouterRuntime.state.value.running)
        http.newCall(Request.Builder().url("http://127.0.0.1:${store.config.value.port}/v1/models")
            .header("Authorization", "Bearer ${store.config.value.accessKey}").build()).execute().use { response ->
            assertEquals(200, response.code)
        }
        assertFalse(McpRuntime.state.value.running)

        compose.onNode(hasText(connection.name) and hasClickAction()).performClick()
        compose.onNode(hasText("MCP") and hasClickAction()).assertDoesNotExist()
        context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("open_mcp", true))
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText("MCP") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("MCP") and hasClickAction()).assertIsSelected()
        compose.onNodeWithContentDescription("返回").assertDoesNotExist()
        compose.captureSettingsScreenshot("22-mcp-notification-entry")
        compose.onNodeWithContentDescription("添加连接").performClick()
        compose.onNodeWithText("MCP URL").assertIsDisplayed()
        context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("open_mcp", true))
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText("MCP URL")).fetchSemanticsNodes().isEmpty()
        }
        compose.onNode(hasText("MCP") and hasClickAction()).assertIsSelected()
    }
}
