package dev.pathpilot.app.ui.settings

import android.accessibilityservice.AccessibilityService
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pathpilot.app.MainActivity
import dev.pathpilot.app.runtime.AppStore
import dev.pathpilot.app.settings.AppLanguage
import dev.pathpilot.app.settings.BackupSnapshot
import dev.pathpilot.app.settings.ThemeMode
import dev.pathpilot.core.ProviderConfig
import dev.pathpilot.core.ProviderKind
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** User choices go through production MainActivity and remain after activity recreation. */
@RunWith(AndroidJUnit4::class)
class SettingsFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var store: AppStore
    private lateinit var original: BackupSnapshot
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun prepare() {
        store = captureStore()
        original = store.backupSnapshot(includeCodexSessions = true)
        prepareChineseCapture(store)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(30_000) { compose.onAllNodes(hasContentDescription("设置")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("设置").performClick()
    }

    @After fun clean() {
        scenario?.close()
        if (::original.isInitialized) store.restoreBackup(original, restoreConnectionSettings = true)
    }

    @Test fun themeMonetAndLanguageChoicesPersistInTheNativeActivity() {
        compose.onNodeWithText("主题").assertIsDisplayed()
        compose.captureSettingsScreenshot("01-settings-light")
        compose.onNodeWithText("主题").performClick()
        compose.onNodeWithText("浅色").assertIsDisplayed()
        compose.onNodeWithText("深色").assertIsDisplayed()
        compose.captureSettingsScreenshot("02-theme-picker")
        compose.onNodeWithText("深色").performClick()
        compose.waitUntil(30_000) { store.preferences.value.themeMode == ThemeMode.DARK }
        compose.onNodeWithText("莫奈取色").performClick()
        compose.waitUntil(30_000) { !store.preferences.value.dynamicColor }
        scenario!!.recreate()
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("深色")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("深色").assertIsDisplayed()
        compose.onNodeWithText("莫奈取色").assertIsOff()
        compose.captureSettingsScreenshot("03-settings-dark")

        compose.onNodeWithText("主题").performClick()
        compose.onNodeWithText("浅色").performClick()
        compose.waitUntil(30_000) { store.preferences.value.themeMode == ThemeMode.LIGHT }
        compose.onNodeWithText("莫奈取色").performClick()
        compose.waitUntil(30_000) { store.preferences.value.dynamicColor }
        compose.onNodeWithText("语言").performClick()
        compose.captureSettingsScreenshot("04-language-picker")
        compose.onNodeWithText("English").performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("Theme")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(AppLanguage.EN, store.preferences.value.language)
        scenario!!.recreate()
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("Theme")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Theme").assertIsDisplayed()
        compose.onNodeWithText("Language").assertIsDisplayed()
        compose.onNodeWithText("Wallpaper colors").assertIsOn()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        navigation("MCP").assertDoesNotExist()
        compose.captureSettingsScreenshot("05-settings-english")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("About PathPilot"))
        compose.onNodeWithText("Export backup").assertIsDisplayed()
        compose.onNodeWithText("Restore backup").assertIsDisplayed()
        compose.captureSettingsScreenshot("06-settings-backup-english")
    }

    @Test fun primaryPagesOpenSettingsAndReturnToTheSameDestination() {
        store.setConfig(store.config.value.copy(
            providers = (1..12).map { index -> ProviderConfig(
                id = "navigation-$index", name = "离线供应商 $index", kind = ProviderKind.OPENAI_COMPATIBLE,
                baseUrl = "https://api.example.test/v1",
            ) },
            routes = emptyList(),
        ))
        navigation("MCP").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").performClick()
        listOf("共享", "供应商", "模型", "统计", "MCP").forEachIndexed { index, label ->
            navigation(label).performClick().assertIsSelected()
            compose.onNodeWithContentDescription("设置").assertIsDisplayed()
            compose.captureSettingsScreenshot("${17 + index}-primary-$index")
            if (label == "供应商") compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("离线供应商 12"))
            compose.onNodeWithContentDescription("设置").performClick()
            compose.onNodeWithText("主题").assertIsDisplayed()
            navigation("MCP").assertDoesNotExist()
            compose.onNodeWithContentDescription("返回").performClick()
            navigation(label).assertIsSelected()
            if (label == "供应商") compose.onNodeWithText("离线供应商 12").assertIsDisplayed()
        }
        compose.onNodeWithContentDescription("返回").assertDoesNotExist()
    }

    @Test fun exportUsesTheAndroidDocumentPickerAndCanBeCancelled() {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("导出备份"))
        compose.onNodeWithText("导出备份").performClick()
        compose.onNodeWithText("密码").performTextReplacement("offline-picker-password")
        compose.onNodeWithText("确认密码").performTextReplacement("offline-picker-password")
        hideCaptureKeyboard()
        compose.onNodeWithText("继续").performClick()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        compose.waitUntil(180_000) {
            automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true
        }
        compose.captureSettingsScreenshot("13-backup-system-picker")
        assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText("导出备份") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(original.config.providers, store.config.value.providers)
    }

    /** Invoke separately with actual font_scale=1.5 and a 320dp viewport. */
    @Test fun largeSystemFontKeepsSettingsAndMcpControlsReachable() {
        scenario!!.onActivity {
            assertEquals(1.5f, it.resources.configuration.fontScale, 0.01f)
            assertTrue(it.resources.configuration.screenWidthDp in 320..360)
        }
        navigation("MCP").assertDoesNotExist()
        compose.captureSettingsScreenshot("14-settings-large-font")
        compose.onNodeWithContentDescription("返回").performClick()
        listOf("共享", "供应商", "模型", "统计", "MCP").forEach { label ->
            navigation(label).assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNode(hasText(label) and hasAnyAncestor(hasClickAction()), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            layouts.forEach { result ->
                assertFalse("$label width", result.didOverflowWidth)
                assertFalse("$label height", result.didOverflowHeight)
            }
        }
        compose.captureSettingsScreenshot("14a-navigation-large-font")
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("恢复备份"))
        compose.onNodeWithText("导出备份").performClick()
        compose.onNodeWithText("确认密码").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
        compose.captureSettingsScreenshot("15-backup-large-font")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        navigation("MCP").performClick()
        compose.captureSettingsScreenshot("16a-mcp-root-large-font")
        compose.onNodeWithContentDescription("添加连接").performClick()
        compose.onNodeWithText("名称").assertIsDisplayed()
        compose.onNodeWithText("保存").performScrollTo().assertIsDisplayed()
        compose.captureSettingsScreenshot("16-mcp-editor-large-font")
    }

    private fun navigation(label: String) = compose.onNode(hasText(label) and hasClickAction())
}
