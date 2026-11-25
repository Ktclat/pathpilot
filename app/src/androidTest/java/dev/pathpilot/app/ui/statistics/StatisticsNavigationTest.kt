package dev.pathpilot.app.ui.statistics

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathpilot.app.MainActivity
import dev.pathpilot.app.runtime.AppStore
import dev.pathpilot.core.RequestEvent
import dev.pathpilot.core.RequestOutcome
import dev.pathpilot.core.TokenUsage
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production MainActivity and database. Fixture writes are restricted to the isolated capture package. */
@RunWith(AndroidJUnit4::class)
class StatisticsNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private var store: AppStore? = null

    @Before fun prepareCaptureHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Navigation fixtures require the isolated capture APK", context.packageName.endsWith(".capture"))
        store = AppStore.get(context)
        store!!.requestHistory.clear()
        assertNull(store!!.requestHistory.state.value.error)
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText("模型共享")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @After fun clearCaptureHistory() {
        store?.requestHistory?.clear()
    }

    @Test fun statisticsIsAStandaloneDestinationAndSharingStaysFocused() {
        listOf("共享", "供应商", "模型", "统计", "设置").forEach { label ->
            navigation(label).assertIsDisplayed()
        }
        navigation("共享").assertIsSelected()
        compose.onNodeWithText("今日用量").assertDoesNotExist()
        screenshot("00-sharing-home")

        navigation("统计").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("统计概览").assertIsDisplayed()
        compose.onNodeWithText("暂无请求记录").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").assertDoesNotExist()
        screenshot("05-statistics-empty")
        compose.onNodeWithContentDescription("查看请求记录").performClick()
        navigation("统计").assertIsSelected()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        navigation("共享").assertIsSelected()
        compose.onNodeWithText("模型共享").assertIsDisplayed()

        val history = store!!.requestHistory
        offlineStatisticsEvents(System.currentTimeMillis()).forEach(history::record)
        assertNull(history.state.value.error)
        navigation("统计").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("请求数，4").assertIsDisplayed()
        screenshot("09-statistics-main")
    }

    /** Run separately after setting the actual Android font scale to 1.5 and viewport to 320dp. */
    @Test fun largeSystemFontKeepsNavigationMetricsAndTrendReachable() {
        assumeTrue("Run this layout audit at font_scale=1.5 and width 320–360dp",
            compose.activity.resources.configuration.fontScale in 1.49f..1.51f &&
                compose.activity.resources.configuration.screenWidthDp in 320..360)
        assertEquals(1.5f, compose.activity.resources.configuration.fontScale, 0.01f)
        assertTrue(compose.activity.resources.configuration.screenWidthDp in 320..360)
        val now = System.currentTimeMillis()
        val history = store!!.requestHistory
        history.record(RequestEvent("daily-codex-long-model-2026-preview", "offline-codex", 200, 2_300,
            "/v1/responses", id = "offline-large-success", startedAtEpochMs = now,
            providerName = "Codex · 离线测试", upstreamModel = "fixture-upstream-long-model-id",
            usage = TokenUsage(inputTokens = 1_234_567_890, outputTokens = 987_654_321, totalTokens = 2_222_222_211)))
        history.record(RequestEvent("unknown-model", "offline-compatible", 200, 1_200,
            "/v1/chat/completions", id = "offline-large-unknown", startedAtEpochMs = now - 60_000,
            providerName = "兼容 API · 离线测试", upstreamModel = "fixture-unknown", usage = null))
        history.record(RequestEvent("interrupted", "offline-codex", 200, 4_000,
            "/v1/responses", id = "offline-large-interrupted", startedAtEpochMs = now - 120_000,
            providerName = "Codex · 离线测试", upstreamModel = "fixture-unknown", usage = null,
            outcome = RequestOutcome.INTERRUPTED, errorCode = "stream_interrupted"))
        assertNull(history.state.value.error)
        compose.waitForIdle()

        navigation("统计").performClick().assertIsSelected()
        listOf("共享", "供应商", "模型", "统计", "设置").forEach { label ->
            navigation(label).assertIsDisplayed()
            assertNavigationTextFits(label)
        }
        compose.onNodeWithContentDescription("请求数，3").assertIsDisplayed()
        screenshot("06-statistics-large-font")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("趋势：请求"))
        compose.onNodeWithContentDescription("趋势：请求").assertIsDisplayed()
        screenshot("07-trend-large-font")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("按模型查看统计"))
        compose.onNodeWithContentDescription("按模型查看统计").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("daily-codex-long-model-2026-preview"))
        screenshot("08-models-large-font")
    }

    private fun navigation(label: String) = compose.onNode(hasText(label) and hasClickAction())

    private fun assertNavigationTextFits(label: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(label) and hasAnyAncestor(hasClickAction()), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
        assertTrue("Navigation label $label has a text layout", layouts.isNotEmpty())
        layouts.forEach { layout ->
            assertFalse("Navigation label $label overflows horizontally", layout.didOverflowWidth)
            assertFalse("Navigation label $label overflows vertically", layout.didOverflowHeight)
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "statistics-e2e")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()
    }
}
