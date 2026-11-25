package dev.pathpilot.app

import dev.pathpilot.core.RequestEvent
import dev.pathpilot.core.RequestOutcome
import dev.pathpilot.core.TokenUsage
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageAnalyticsTest {
    @Test fun todayUsesLocalCalendarMidnightAndExcludesNextDay() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = epoch("2026-11-25T04:00:00Z")
        val events = listOf(
            event("before", at = epoch("2026-11-24T15:59:59Z")),
            event("midnight", at = epoch("2026-11-24T16:00:00Z")),
            event("evening", at = epoch("2026-11-25T15:59:59Z")),
            event("tomorrow", at = epoch("2026-11-25T16:00:00Z")),
        )
        assertEquals(listOf("evening", "midnight"), UsageAnalytics.filter(events, UsageRange.TODAY,
            nowEpochMs = now, zoneId = zone).map { it.id })
    }

    @Test fun sevenDaysIncludesTodayAndFollowsDaylightSavingCalendar() {
        val zone = ZoneId.of("America/New_York")
        val now = epoch("2026-11-03T17:00:00Z")
        val events = listOf(
            event("outside", at = epoch("2026-10-28T03:59:59Z")),
            event("first", at = epoch("2026-10-28T04:00:00Z")),
            event("last", at = epoch("2026-11-04T04:59:59Z")),
            event("tomorrow", at = epoch("2026-11-04T05:00:00Z")),
        )
        assertEquals(listOf("last", "first"), UsageAnalytics.filter(events, UsageRange.LAST_7_DAYS,
            nowEpochMs = now, zoneId = zone).map { it.id })
        assertEquals(4, UsageAnalytics.filter(events, UsageRange.ALL, nowEpochMs = now, zoneId = zone).size)
    }

    @Test fun missingUsageRemainsUnknownWhileRealZeroIsPreserved() {
        val unknown = UsageAnalytics.summarize(listOf(event("absent"), event("empty", usage = TokenUsage())))
        assertEquals(0, unknown.usageReportedCount)
        assertNull(unknown.inputTokens)
        assertNull(unknown.outputTokens)
        assertNull(unknown.totalTokens)

        val zero = UsageAnalytics.summarize(listOf(event("zero", usage = TokenUsage(inputTokens = 0, outputTokens = 0))))
        assertEquals(1, zero.usageReportedCount)
        assertEquals(0L, zero.totalTokens)
    }

    @Test fun partialCountersDoNotTurnIntoCompleteTotals() {
        val result = UsageAnalytics.summarize(listOf(
            event("input-only", usage = TokenUsage(inputTokens = 10)),
            event("both", usage = TokenUsage(inputTokens = 3, outputTokens = 4, cachedInputTokens = 2)),
            event("reported-total", usage = TokenUsage(totalTokens = 8, reasoningOutputTokens = 5)),
            event("unreported"),
        ))
        assertEquals(4, result.requestCount)
        assertEquals(3, result.usageReportedCount)
        assertEquals(13L, result.inputTokens)
        assertEquals(4L, result.outputTokens)
        assertEquals(15L, result.totalTokens)
        assertEquals(2L, result.cachedInputTokens)
        assertEquals(5L, result.reasoningOutputTokens)
        assertNull(UsageAnalytics.summarize(listOf(event("partial", usage = TokenUsage(inputTokens = 10)))).totalTokens)
    }

    @Test fun groupingKeepsProviderAndUpstreamModelsSeparateAndUsesLatestName() {
        val groups = UsageAnalytics.byModel(listOf(
            event("a1", provider = "one", upstream = "first", at = 1, name = "Old"),
            event("a2", provider = "one", upstream = "first", at = 2, name = "New"),
            event("b", provider = "two", upstream = "first"),
            event("c", provider = "one", upstream = "second"),
        ))
        assertEquals(3, groups.size)
        assertEquals(ModelUsageKey("one", "daily", "first"), groups.first().key)
        assertEquals(2, groups.first().summary.requestCount)
        assertEquals("New", groups.first().providerName)
    }

    @Test fun tokenTotalsDoNotOverflowIntoNegativeCounts() {
        val overflow = TokenUsage(inputTokens = Long.MAX_VALUE, outputTokens = 1)
        assertNull(UsageAnalytics.totalTokens(overflow))
        assertNull(UsageAnalytics.summarize(listOf(event("overflow", usage = overflow))).totalTokens)
        val result = UsageAnalytics.summarize(listOf(
            event("max", usage = TokenUsage(inputTokens = Long.MAX_VALUE, totalTokens = Long.MAX_VALUE)),
            event("one", usage = TokenUsage(inputTokens = 1, totalTokens = 1)),
        ))
        assertNull(result.inputTokens)
        assertNull(result.totalTokens)
        assertEquals(2, result.usageReportedCount)
    }

    @Test fun queryOutcomeAndModelScopeCombineWithoutChangingHttpSuccessMeaning() {
        val events = listOf(
            event("done", at = 1, name = "Codex", outcome = RequestOutcome.SUCCESS),
            event("broken", at = 2, name = "Codex", outcome = RequestOutcome.INTERRUPTED),
            event("other", at = 3, provider = "other", name = "Codex", outcome = RequestOutcome.INTERRUPTED),
            event("failed", at = 4, model = "review", name = "DeepSeek", outcome = RequestOutcome.HTTP_ERROR),
        )
        assertEquals(listOf("broken"), UsageAnalytics.filter(events, query = " CODEX ",
            outcome = RequestOutcome.INTERRUPTED, providerId = "one", model = "daily", upstreamModel = "remote").map { it.id })
        assertEquals(listOf("other", "broken", "done"), UsageAnalytics.filter(events, query = "DAILY").map { it.id })
        assertEquals(4, UsageAnalytics.filter(events, query = "REMOTE").size)
        val result = UsageAnalytics.summarize(events)
        assertEquals(1, result.successCount)
        assertEquals(1, result.errorCount)
        assertEquals(2, result.interruptedCount)
    }

    private fun event(
        id: String,
        at: Long = 0,
        provider: String = "one",
        model: String = "daily",
        upstream: String = "remote",
        name: String = provider,
        usage: TokenUsage? = null,
        outcome: RequestOutcome = RequestOutcome.SUCCESS,
    ) = RequestEvent(model, provider, 200, 100, "/v1/responses", id = id,
        startedAtEpochMs = at, providerName = name, upstreamModel = upstream, usage = usage, outcome = outcome)

    private fun epoch(text: String) = Instant.parse(text).toEpochMilli()
}
