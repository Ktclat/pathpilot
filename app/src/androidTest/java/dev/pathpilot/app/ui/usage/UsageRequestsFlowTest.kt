package dev.pathpilot.app.ui.usage

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathpilot.app.UsageRequestsPage
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
class UsageRequestsFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var events: MutableState<List<RequestEvent>>
    private var clearCalls = 0
    private val started = System.currentTimeMillis()
    private val fixture = listOf(
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

    @Test fun modelUsageOpensOnlyThatModelsRequests() {
        mount()
        compose.onNodeWithText("用量与请求").assertIsDisplayed()
        compose.onNodeWithText(NumberFormat.getIntegerInstance().format(1_380)).assertIsDisplayed()
        compose.onNodeWithText("1/4 条请求返回用量").assertIsDisplayed()
        screenshot("01-model-usage")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("daily"))
        compose.onNodeWithText("daily").performClick()
        compose.onAllNodesWithText("daily").onFirst().assertIsDisplayed()
        compose.onNodeWithText("review").assertDoesNotExist()
        compose.onNodeWithText("deepseek-chat").assertDoesNotExist()
        compose.onNodeWithText("external").assertDoesNotExist()
    }

    @Test fun interruptedHttp200ShowsOutcomeAndUnknownUsageInDetails() {
        mount(requests = true)
        compose.onNodeWithText("review").assertIsDisplayed()
        screenshot("02-recent-requests")
        compose.onNodeWithText("review").performClick()
        compose.onNodeWithText("请求详情").assertIsDisplayed()
        compose.onNode(hasText("中断") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithText("HTTP 状态").assertIsDisplayed()
        compose.onNodeWithText("200").assertIsDisplayed()
        screenshot("03-request-details")
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

    private fun mount(requests: Boolean = false) {
        events = mutableStateOf(fixture)
        clearCalls = 0
        compose.setContent {
            PathPilotTheme {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    UsageRequestsPage(
                        events = events.value,
                        error = null,
                        initialRequests = requests,
                        onBack = {},
                        onClear = { clearCalls++; events.value = emptyList() },
                        onRetry = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        // Use all retained records so this fixture also works across a local midnight boundary.
        compose.onNodeWithText("全部").performClick()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "usage-requests-e2e")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()
    }
}
