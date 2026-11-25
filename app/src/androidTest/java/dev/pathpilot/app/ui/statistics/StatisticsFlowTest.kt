package dev.pathpilot.app.ui.statistics

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathpilot.app.StatisticsPage
import dev.pathpilot.app.ui.theme.PathPilotTheme
import dev.pathpilot.core.RequestEvent
import dev.pathpilot.core.RequestOutcome
import dev.pathpilot.core.TokenUsage
import java.io.File
import java.text.NumberFormat
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real usage/request UI with explicit offline metadata; no stored account or network request. */
@RunWith(AndroidJUnit4::class)
class StatisticsFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var events: MutableState<List<RequestEvent>>
    private var clearCalls = 0
    private val started = System.currentTimeMillis()
    private val fixture = offlineStatisticsEvents(started)

    @Test fun modelUsageOpensOnlyThatModelsRequests() {
        mount()
        compose.onNodeWithText("统计").assertIsDisplayed()
        compose.onNodeWithContentDescription("请求数，4").assertIsDisplayed()
        compose.onNodeWithContentDescription("Token，${NumberFormat.getIntegerInstance().format(1_380)}").assertIsDisplayed()
        compose.onNodeWithContentDescription("成功率，50%").assertIsDisplayed()
        screenshot("01-statistics-overview")
        overviewList().performScrollToNode(hasContentDescription("按模型查看统计"))
        compose.onNodeWithContentDescription("按模型查看统计").performClick()
        overviewList().performScrollToNode(hasText("daily"))
        screenshot("02-model-breakdown")
        compose.onNodeWithText("daily").performClick()
        compose.onAllNodesWithText("daily").onFirst().assertIsDisplayed()
        compose.onNodeWithText("review").assertDoesNotExist()
        compose.onNodeWithText("deepseek-chat").assertDoesNotExist()
        compose.onNodeWithText("external").assertDoesNotExist()
    }

    @Test fun interruptedHttp200ShowsOutcomeAndUnknownUsageInDetails() {
        mount(requests = true)
        compose.onNodeWithText("review").assertIsDisplayed()
        screenshot("03-recent-requests")
        compose.onNodeWithText("review").performClick()
        compose.onNodeWithText("请求详情").assertIsDisplayed()
        compose.onNode(hasText("中断") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithText("HTTP 状态").assertIsDisplayed()
        compose.onNodeWithText("200").assertIsDisplayed()
        screenshot("04-request-details")
        compose.onNode(hasScrollToNodeAction() and hasAnyAncestor(isDialog()))
            .performScrollToNode(hasText("输入"))
        compose.onAllNodesWithText("未提供").onFirst().assertIsDisplayed()
    }

    @Test fun statusAndSearchFilterRequestsAndClearRequiresConfirmation() {
        mount(requests = true)
        compose.onNodeWithText("全部状态").performClick()
        compose.onNode(hasText("失败") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText("deepseek-chat").assertIsDisplayed()
        compose.onNodeWithText("daily").assertDoesNotExist()
        compose.onNodeWithText("review").assertDoesNotExist()
        val search = compose.onNodeWithText("搜索模型或供应商")
        search.performTextReplacement("no-match")
        search.performImeAction()
        compose.onNodeWithText("deepseek-chat").assertDoesNotExist()
        search.performTextReplacement("DeepSeek")
        search.performImeAction()
        compose.onNodeWithText("deepseek-chat").assertIsDisplayed()

        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("清空记录").performClick()
        compose.onNodeWithText("清空请求记录？").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, clearCalls); assertEquals(fixture, events.value) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, clearCalls); assertEquals(fixture, events.value) }
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("清空记录").performClick()
        compose.onNodeWithText("清空").performClick()
        compose.runOnIdle { assertEquals(1, clearCalls); assertEquals(emptyList<RequestEvent>(), events.value) }
    }

    @Test fun missingTokenAndReportedZeroStayDifferent() {
        val missing = fixture.single { it.id == "offline-missing-usage" }
        mount(records = listOf(missing))
        compose.onNodeWithContentDescription("请求数，1").assertIsDisplayed()
        compose.onNodeWithContentDescription("Token，未提供").assertIsDisplayed()
        overviewList().performScrollToNode(hasContentDescription("趋势：Token"))
        compose.onNodeWithContentDescription("趋势：Token").performClick()
        overviewList().performScrollToNode(hasContentDescription("未提供 Token", substring = true))
        compose.onNode(hasContentDescription("未提供 Token", substring = true)).assertIsDisplayed()
        compose.runOnIdle {
            events.value = listOf(missing.copy(usage = TokenUsage(inputTokens = 0, outputTokens = 0, totalTokens = 0)))
        }
        compose.onNode(hasContentDescription("0 Token", substring = true)).assertIsDisplayed()
        compose.onNode(hasContentDescription("未提供 Token", substring = true)).assertDoesNotExist()
        overviewList().performScrollToNode(hasContentDescription("Token，0"))
        compose.onNodeWithContentDescription("Token，0").assertIsDisplayed()
        compose.onNodeWithContentDescription("Token，未提供").assertDoesNotExist()
    }

    @Test fun providerDrilldownKeepsTheChosenDateRange() {
        val older = fixture[1].copy(model = "older-model", id = "offline-old", startedAtEpochMs = started - 40L * 86_400_000)
        mount(records = fixture + older)
        compose.onNodeWithText("7天").performScrollTo().performClick()
        compose.onNodeWithContentDescription("请求数，4").assertIsDisplayed()
        overviewList().performScrollToNode(hasContentDescription("按供应商查看统计"))
        compose.onNodeWithContentDescription("按供应商查看统计").performClick()
        overviewList().performScrollToNode(hasText("Codex · 离线测试"))
        compose.onNodeWithText("Codex · 离线测试").performClick()
        compose.onNodeWithText("review").assertIsDisplayed()
        compose.onNodeWithText("daily").assertIsDisplayed()
        compose.onNodeWithText("older-model").assertDoesNotExist()
        compose.onNodeWithText("deepseek-chat").assertDoesNotExist()
        compose.onNodeWithText("external").assertDoesNotExist()
    }

    private fun mount(requests: Boolean = false, records: List<RequestEvent> = fixture) {
        events = mutableStateOf(records)
        clearCalls = 0
        compose.setContent {
            PathPilotTheme {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    StatisticsPage(
                        events = events.value,
                        error = null,
                        initialRequests = requests,
                        onClear = { clearCalls++; events.value = emptyList() },
                        onRetry = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        // Use all retained records so this fixture also works across a local midnight boundary.
        compose.onNodeWithText("全部").performScrollTo().performClick()
    }

    private fun overviewList() = compose.onNode(hasScrollToNodeAction())

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

internal fun offlineStatisticsEvents(started: Long): List<RequestEvent> = listOf(
        RequestEvent("review", "offline-codex", 200, 4_200, "/v1/responses",
            id = "offline-interrupted", startedAtEpochMs = started - 60_000,
            providerName = "Codex · 离线测试", upstreamModel = "gpt-5.3-codex", streaming = true,
            usage = null, outcome = RequestOutcome.INTERRUPTED, errorCode = "stream_interrupted"),
        RequestEvent("daily", "offline-codex", 200, 2_300, "/v1/chat/completions",
            id = "offline-success", startedAtEpochMs = started - 120_000,
            providerName = "Codex · 离线测试", upstreamModel = "gpt-5.4", streaming = true,
            usage = TokenUsage(inputTokens = 1_200, outputTokens = 180, totalTokens = 1_380,
                cachedInputTokens = 800, reasoningOutputTokens = 70), outcome = RequestOutcome.SUCCESS),
        RequestEvent("deepseek-chat", "offline-deepseek", 429, 800, "/v1/chat/completions",
            id = "offline-http-error", startedAtEpochMs = started - 180_000,
            providerName = "DeepSeek · 离线测试", upstreamModel = "deepseek-chat", streaming = false,
            usage = null, outcome = RequestOutcome.HTTP_ERROR, errorCode = "upstream_error"),
        RequestEvent("external", "offline-compatible", 200, 1_100, "/v1/chat/completions",
            id = "offline-missing-usage", startedAtEpochMs = started - 240_000,
            providerName = "兼容 API · 离线测试", upstreamModel = "remote-model", streaming = false,
            usage = null, outcome = RequestOutcome.SUCCESS),
    )
