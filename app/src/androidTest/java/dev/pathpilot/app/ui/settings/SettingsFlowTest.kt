package dev.pathpilot.app.ui.settings

import android.accessibilityservice.AccessibilityService
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pathpilot.app.MainActivity
import dev.pathpilot.app.runtime.AppStore
import dev.pathpilot.app.settings.AppLanguage
import dev.pathpilot.app.settings.BackupSnapshot
import dev.pathpilot.app.settings.ThemeMode
import dev.pathpilot.app.settings.ThemePalette
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
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("设置") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        navigation("设置").performClick()
    }

    @After fun clean() {
        try {
            scenario?.close()
        } finally {
            if (::original.isInitialized) store.restoreBackup(original, restoreConnectionSettings = true)
        }
    }

    @Test fun themePaletteDynamicAndLanguageChoicesPersistInTheNativeActivity() {
        compose.onNodeWithText("主题").assertIsDisplayed()
        compose.onNodeWithText("莫奈取色").assertDoesNotExist()
        compose.captureSettingsScreenshot("26-settings-theme-entry")
        compose.onNodeWithText("主题").performClick()
        compose.onNodeWithText("浅色").assertIsDisplayed()
        compose.onNodeWithText("深色").assertIsDisplayed()
        compose.onNodeWithText("动态取色").assertIsOn()
        compose.captureSettingsScreenshot("27-theme-dynamic")
        compose.onNodeWithText("深色").performClick()
        compose.waitUntil(30_000) { store.preferences.value.themeMode == ThemeMode.DARK }
        themeOption("海蓝").performClick()
        compose.waitUntil(30_000) {
            !store.preferences.value.dynamicColor && store.preferences.value.themePalette == ThemePalette.OCEAN
        }
        compose.captureSettingsScreenshot("28-theme-ocean-dark")
        compose.onNodeWithText("完成").performClick()
        scenario!!.recreate()
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("主题")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(ThemeMode.DARK, store.preferences.value.themeMode)
        assertEquals(ThemePalette.OCEAN, store.preferences.value.themePalette)
        assertFalse(store.preferences.value.dynamicColor)
        compose.onNodeWithText("主题").performClick()
        compose.onNodeWithText("动态取色").assertIsOff()
        themeOption("海蓝").assertIsSelected()
        compose.captureSettingsScreenshot("29-theme-ocean-restored")
        themeOption("动态取色").performClick()
        compose.waitUntil(30_000) { store.preferences.value.dynamicColor }
        assertEquals(ThemePalette.OCEAN, store.preferences.value.themePalette)
        themeOption("浅色").performClick()
        compose.waitUntil(30_000) { store.preferences.value.themeMode == ThemeMode.LIGHT }
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("语言").performClick()
        compose.onNodeWithText("English").performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("Theme")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(AppLanguage.EN, store.preferences.value.language)
        scenario!!.recreate()
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("Theme")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Theme").assertIsDisplayed()
        compose.onNodeWithText("Language").assertIsDisplayed()
        assertEquals(ThemeMode.LIGHT, store.preferences.value.themeMode)
        assertTrue(store.preferences.value.dynamicColor)
        assertEquals(ThemePalette.OCEAN, store.preferences.value.themePalette)
        navigation("Settings").assertIsSelected()
        navigation("MCP").assertIsDisplayed()
        compose.onNodeWithText("Theme").performClick()
        compose.onNodeWithText("Dynamic colors").assertIsOn()
        compose.captureSettingsScreenshot("30-theme-dynamic-english")
        themeOption("Amber").performClick()
        compose.waitUntil(30_000) {
            !store.preferences.value.dynamicColor && store.preferences.value.themePalette == ThemePalette.AMBER
        }
        compose.captureSettingsScreenshot("31-theme-amber-english")
        compose.onNodeWithText("Done").performClick()
        compose.captureSettingsScreenshot("32-settings-english-theme")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("About PathPilot"))
        compose.onNodeWithText("Export backup").assertIsDisplayed()
        compose.onNodeWithText("Restore backup").assertIsDisplayed()
        compose.captureSettingsScreenshot("06-settings-backup-english")
    }

    @Test fun fourPrimaryPagesPreserveListsAndUseSecondaryDetails() {
        store.setConfig(store.config.value.copy(
            providers = (1..12).map { index -> ProviderConfig(
                id = "navigation-$index", name = "离线供应商 $index", kind = ProviderKind.OPENAI_COMPATIBLE,
                baseUrl = "https://api.example.test/v1",
            ) },
            routes = emptyList(),
        ))
        listOf("共享", "模型", "MCP", "设置").forEachIndexed { index, label ->
            navigation(label).performClick().assertIsSelected()
            navigation("供应商").assertDoesNotExist()
            navigation("统计").assertDoesNotExist()
            compose.onNodeWithContentDescription("设置").assertDoesNotExist()
            compose.onNodeWithContentDescription("返回").assertDoesNotExist()
            compose.captureSettingsScreenshot("${17 + index}-primary-$index")
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("关于 PathPilot"))
        compose.onNodeWithText("关于 PathPilot").assertIsDisplayed()
        navigation("模型").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("离线供应商 12"))
        compose.onNodeWithContentDescription("统计").performClick()
        navigation("设置").assertDoesNotExist()
        compose.onNodeWithContentDescription("统计概览").assertIsDisplayed()
        compose.captureSettingsScreenshot("21-statistics-secondary")
        compose.onNodeWithContentDescription("返回").performClick()
        navigation("模型").assertIsSelected()
        compose.onNodeWithText("离线供应商 12").assertIsDisplayed()
        compose.onNodeWithText("离线供应商 12").performClick()
        navigation("设置").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回供应商列表").assertIsDisplayed()
        compose.captureSettingsScreenshot("21a-provider-models-secondary")
        compose.onNodeWithContentDescription("返回供应商列表").performClick()
        navigation("模型").assertIsSelected()
        compose.onNodeWithText("离线供应商 12").assertIsDisplayed()
        navigation("设置").performClick().assertIsSelected()
        compose.onNodeWithText("关于 PathPilot").assertIsDisplayed()
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
        navigation("设置").assertIsSelected()
        compose.captureSettingsScreenshot("14-settings-large-font")
        listOf("共享", "模型", "MCP", "设置").forEach { label ->
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
        compose.onNodeWithText("主题").performClick()
        compose.onNodeWithText("动态取色").assertIsDisplayed()
        themeOption("琥珀").performClick()
        compose.waitUntil(30_000) {
            store.preferences.value.themePalette == ThemePalette.AMBER && !store.preferences.value.dynamicColor
        }
        themeOption("琥珀").assertIsSelected()
        compose.captureSettingsScreenshot("33-theme-palette-large-font")
        themeOption("动态取色").performClick()
        compose.waitUntil(30_000) { store.preferences.value.dynamicColor }
        compose.captureSettingsScreenshot("34-theme-dynamic-large-font")
        compose.onNodeWithText("完成").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("恢复备份"))
        compose.onNodeWithText("导出备份").performClick()
        compose.onNodeWithText("确认密码").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
        compose.captureSettingsScreenshot("15-backup-large-font")
        compose.onNodeWithText("取消").performClick()
        navigation("MCP").performClick()
        compose.captureSettingsScreenshot("16a-mcp-root-large-font")
        compose.onNodeWithContentDescription("添加连接").performClick()
        compose.onNodeWithTag("mcp-preset-github").performScrollTo().assertIsDisplayed()
        compose.captureSettingsScreenshot("35-mcp-presets-large-font")
        compose.onNodeWithTag("mcp-preset-github").performClick()
        compose.onNodeWithTag("mcp-preset-token").assertIsDisplayed()
        compose.onNodeWithTag("mcp-preset-add").performScrollTo().assertIsDisplayed()
        compose.captureSettingsScreenshot("36-mcp-preset-token-large-font")
        compose.onNodeWithContentDescription("返回服务列表").performClick()
        compose.onNodeWithTag("mcp-preset-custom").performScrollTo().performClick()
        compose.onNodeWithText("名称").assertIsDisplayed()
        compose.onNodeWithText("保存").performScrollTo().assertIsDisplayed()
        compose.captureSettingsScreenshot("16-mcp-editor-large-font")
    }

    private fun themeOption(label: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("theme-options-scroll").performScrollToNode(hasText(label))
        return compose.onNodeWithText(label)
    }

    private fun navigation(label: String) = compose.onNode(hasText(label) and hasClickAction())
}
