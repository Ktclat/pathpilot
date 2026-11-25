package dev.pathpilot.app.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathpilot.app.SettingsScreen
import dev.pathpilot.app.runtime.AppStore
import dev.pathpilot.app.settings.BackupCodec
import dev.pathpilot.app.settings.BackupSnapshot
import dev.pathpilot.app.ui.theme.PathPilotTheme
import dev.pathpilot.core.ProviderConfig
import dev.pathpilot.core.ProviderKind
import dev.pathpilot.core.RouteConfig
import dev.pathpilot.core.mcp.McpConnection
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production SettingsScreen and encrypted file I/O; only the system picker result is supplied. */
@RunWith(AndroidJUnit4::class)
class BackupFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var store: AppStore
    private lateinit var original: BackupSnapshot
    private lateinit var file: File
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private val pickerActions = CopyOnWriteArrayList<String>()
    private val messages = CopyOnWriteArrayList<String>()

    @Before fun prepare() {
        store = captureStore()
        original = store.backupSnapshot(includeCodexSessions = true)
        prepareChineseCapture(store)
        store.setConfig(store.config.value.copy(providers = listOf(
            ProviderConfig("backup-api", "API · 离线测试", ProviderKind.OPENAI_COMPATIBLE,
                "https://api.example.test/v1", apiKey = "offline-backup-key"),
        ), routes = listOf(RouteConfig("offline-model", "backup-api", "offline-upstream"))))
        store.setMcpSettings(store.mcp.value.copy(connections = listOf(
            McpConnection(id = "backup-tools", name = "工具 · 离线测试", url = "https://mcp.example.test/mcp"),
        )))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File(context.cacheDir, "settings-flow.ppbackup").apply { delete() }
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                pickerActions += contract.createIntent(context, input).action.orEmpty()
                dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario!!.onActivity { activity ->
            activity.setContent {
                val preferences by store.preferences.collectAsState()
                PathPilotTheme(preferences) {
                    Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                        CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                            SettingsScreen(store, onMcp = {}, onMessage = messages::add)
                        }
                    }
                }
            }
        }
    }

    @After fun clean() {
        scenario?.close()
        if (::original.isInitialized) store.restoreBackup(original, restoreConnectionSettings = true)
        if (::file.isInitialized) file.delete()
    }

    @Test fun exportUnlockPreviewAndRestoreUseTheEncryptedFile() {
        val exportedConfig = store.config.value
        val password = "offline-backup-password"
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("导出备份"))
        compose.onNodeWithText("导出备份").performClick()
        compose.onNodeWithText("密码").performTextReplacement(password)
        compose.onNodeWithText("确认密码").performTextReplacement(password)
        closeSoftKeyboard()
        compose.onNodeWithText("包含订阅登录状态").assertIsOff()
        compose.captureSettingsScreenshot("07-backup-export")
        compose.onNodeWithText("继续").performClick()
        compose.waitUntil(60_000) { messages.contains("备份已导出") }
        assertEquals(listOf(Intent.ACTION_CREATE_DOCUMENT), pickerActions.toList())
        val snapshot = BackupCodec.decode(file.readBytes(), password.toCharArray())
        assertEquals(exportedConfig, snapshot.config)
        assertFalse(snapshot.includesCodexSessions)

        store.setConfig(exportedConfig.copy(port = 8347, providers = emptyList(), routes = emptyList()))
        compose.onNodeWithText("恢复备份").performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("解锁备份")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, pickerActions.last())
        compose.onNodeWithText("密码").performTextReplacement("incorrect-password")
        closeSoftKeyboard()
        compose.onNodeWithText("继续").performClick()
        compose.waitUntil(60_000) { compose.onAllNodes(hasText("密码不正确或备份已损坏")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(store.config.value.providers.isEmpty())
        compose.onNodeWithText("密码").performTextReplacement(password)
        closeSoftKeyboard()
        compose.onNodeWithText("继续").performClick()
        compose.waitUntil(60_000) { compose.onAllNodes(hasText("恢复此备份？")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("1 个供应商 · 1 个模型 · 1 个 MCP 连接").assertIsDisplayed()
        compose.onNodeWithText("恢复连接设置").assertIsOff()
        compose.captureSettingsScreenshot("08-backup-preview")
        compose.onNodeWithText("恢复").performClick()
        compose.waitUntil(30_000) { messages.contains("备份已恢复") }
        assertEquals(exportedConfig.providers, store.config.value.providers)
        assertEquals(exportedConfig.routes, store.config.value.routes)
        assertEquals(8347, store.config.value.port)
        assertEquals(snapshot.mcp.connections, store.mcp.value.connections)
    }
}
