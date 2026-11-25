package dev.pathpilot.app.ui.statistics

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pathpilot.app.StatisticsTrend
import dev.pathpilot.app.UsageRange
import dev.pathpilot.app.UsageTrendBucket
import dev.pathpilot.app.ui.theme.PathPilotTheme
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Restores an actual saved chart selection after the retained history has become shorter. */
@RunWith(AndroidJUnit4::class)
class StatisticsRestorationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun restoredSelectionHandlesFewerTrendBuckets() {
        val firstDay = LocalDate.of(2026, 11, 20)
        val zone = ZoneId.systemDefault()
        val originalBuckets = (0..5).map { index ->
            val day = firstDay.plusDays(index.toLong())
            val requests = when (index) { 0 -> 2; 5 -> 9; else -> 0 }
            UsageTrendBucket(
                startEpochMs = day.atStartOfDay(zone).toInstant().toEpochMilli(),
                endEpochMs = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                label = day.toString(), requestCount = requests, totalTokens = requests * 10L,
                usageReportedCount = requests, totalTokensReportedCount = requests,
            )
        }
        // This list deliberately is not Compose state: it changes only while the old composition
        // is disposed, so saved index 5 is restored directly against the new one-bucket history.
        var buckets = originalBuckets
        var replaceHistoryOnDispose = false
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            DisposableEffect(Unit) {
                onDispose { if (replaceHistoryOnDispose) buckets = originalBuckets.take(1) }
            }
            PathPilotTheme {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    StatisticsTrend(buckets, UsageRange.LAST_7_DAYS)
                }
            }
        }
        compose.onNodeWithContentDescription("2026-11-25 · 9 请求").assertIsDisplayed()
        compose.runOnIdle { replaceHistoryOnDispose = true }
        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithContentDescription("2026-11-20 · 2 请求").assertIsDisplayed()
        compose.onNodeWithContentDescription("上一个时间段").assertIsNotEnabled()
        compose.onNodeWithContentDescription("下一个时间段").assertIsNotEnabled()
        compose.onNodeWithContentDescription("趋势：Token").performClick()
        compose.onNodeWithContentDescription("2026-11-20 · 20 Token").assertIsDisplayed()
    }
}
