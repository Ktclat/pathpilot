package dev.pathpilot.app

import dev.pathpilot.core.RequestEvent
import dev.pathpilot.core.RequestOutcome
import dev.pathpilot.core.TokenUsage
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals(2, result.totalTokensReportedCount)
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

    @Test fun summaryAveragesAllRequestOutcomesAndLeavesEmptyRatesUnknown() {
        val empty = UsageAnalytics.summarize(emptyList())
        assertNull(empty.averageDurationMs)
        assertNull(empty.successRate)
        val summary = UsageAnalytics.summarize(listOf(
            event("ok", durationMs = 100),
            event("failed", durationMs = 201, outcome = RequestOutcome.HTTP_ERROR),
            event("interrupted", durationMs = 1_000, outcome = RequestOutcome.INTERRUPTED),
        ))
        assertEquals(434L, summary.averageDurationMs)
        assertEquals(1.0 / 3.0, summary.successRate!!, 0.000001)
    }

    @Test fun providerGroupsShareTheFilteredScopeAndUseLatestNames() {
        val now = epoch("2026-11-25T12:00:00Z")
        val events = listOf(
            event("old", at = now - 86_400_000, provider = "one", name = "Yesterday", usage = TokenUsage(totalTokens = 100)),
            event("first", at = now - 20_000, provider = "one", name = "Old name", usage = TokenUsage(totalTokens = 10)),
            event("latest", at = now, provider = "one", name = "Latest name", usage = TokenUsage(totalTokens = 20)),
            event("another-model", at = now, provider = "one", model = "review", usage = TokenUsage(totalTokens = 300)),
            event("other-provider", at = now, provider = "two", name = "Second", usage = TokenUsage(totalTokens = 40)),
        )
        val scoped = UsageAnalytics.filter(events, range = UsageRange.TODAY, model = "daily", nowEpochMs = now, zoneId = ZoneId.of("UTC"))
        val groups = UsageAnalytics.byProvider(scoped)
        assertEquals(listOf("one", "two"), groups.map { it.providerId })
        assertEquals("Latest name", groups.first().providerName)
        assertEquals(2, groups.first().summary.requestCount)
        assertEquals(30L, groups.first().summary.totalTokens)
        assertEquals(UsageAnalytics.summarize(scoped).requestCount, groups.sumOf { it.summary.requestCount })
        assertEquals(70L, groups.sumOf { it.summary.totalTokens!! })
    }

    @Test fun thirtyDaysFiltersAndBucketsTheSameLocalCalendarRange() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = epoch("2026-11-25T04:00:00Z")
        val first = epoch("2026-10-26T16:00:00Z")
        val until = epoch("2026-11-25T16:00:00Z")
        val events = listOf(event("before", at = first - 1), event("first", at = first),
            event("last", at = until - 1), event("after", at = until))
        val filtered = UsageAnalytics.filter(events, UsageRange.LAST_30_DAYS, nowEpochMs = now, zoneId = zone)
        assertEquals(listOf("last", "first"), filtered.map { it.id })
        val buckets = UsageAnalytics.trend(events, UsageRange.LAST_30_DAYS, now, zone)
        assertEquals(30, buckets.size)
        assertEquals(first, buckets.first().startEpochMs)
        assertEquals(until, buckets.last().endEpochMs)
        assertEquals("10-27", buckets.first().label)
        assertEquals("11-25", buckets.last().label)
        assertEquals(2, buckets.sumOf { it.requestCount })
        assertEquals(1, buckets.first().requestCount)
        assertEquals(1, buckets.last().requestCount)
    }

    @Test fun hourlyTrendUsesLocalMidnightAndExclusiveBoundaries() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = epoch("2026-11-25T04:00:00Z")
        val first = epoch("2026-11-24T16:00:00Z")
        val until = epoch("2026-11-25T16:00:00Z")
        val events = listOf(event("first", at = first), event("first-end", at = first + 3_599_999),
            event("next-hour", at = first + 3_600_000), event("last", at = until - 1), event("tomorrow", at = until))
        val buckets = UsageAnalytics.trend(events, UsageRange.TODAY, now, zone)
        assertEquals(24, buckets.size)
        assertEquals("00:00", buckets.first().label)
        assertEquals("23:00", buckets.last().label)
        assertEquals(first, buckets.first().startEpochMs)
        assertEquals(until, buckets.last().endEpochMs)
        assertEquals(2, buckets.first().requestCount)
        assertEquals(1, buckets[1].requestCount)
        assertEquals(1, buckets.last().requestCount)
        assertEquals(4, buckets.sumOf { it.requestCount })
        assertTrue(buckets.zipWithNext().all { (left, right) -> left.endEpochMs == right.startEpochMs })
    }

    @Test fun autumnDstHasTwoDistinctRepeatedHourBuckets() {
        val zone = ZoneId.of("America/New_York")
        val events = listOf(event("first-1am", at = epoch("2026-11-01T05:30:00Z")),
            event("second-1am", at = epoch("2026-11-01T06:30:00Z")))
        val buckets = UsageAnalytics.trend(events, UsageRange.TODAY, epoch("2026-11-01T17:00:00Z"), zone)
        val repeated = buckets.filter { it.label.startsWith("01:00") }
        assertEquals(25, buckets.size)
        assertEquals(listOf("01:00 -04:00", "01:00 -05:00"), repeated.map { it.label })
        assertEquals(listOf(1, 1), repeated.map { it.requestCount })
        assertEquals(2, buckets.sumOf { it.requestCount })
        assertEquals(25 * 3_600_000L, buckets.last().endEpochMs - buckets.first().startEpochMs)
    }

    @Test fun springDstSkipsTheNonexistentHour() {
        val zone = ZoneId.of("America/New_York")
        val buckets = UsageAnalytics.trend(listOf(event("3am", at = epoch("2026-03-08T07:00:00Z"))),
            UsageRange.TODAY, epoch("2026-03-08T16:00:00Z"), zone)
        assertEquals(23, buckets.size)
        assertTrue(buckets.none { it.label == "02:00" })
        assertEquals(1, buckets.single { it.label == "03:00" }.requestCount)
        assertEquals(23 * 3_600_000L, buckets.last().endEpochMs - buckets.first().startEpochMs)
    }

    @Test fun trendDistinguishesNoRequestsUnknownTotalsAndPartialCoverage() {
        val now = epoch("2026-11-25T12:00:00Z")
        val events = listOf(
            event("input-only", at = epoch("2026-11-25T08:10:00Z"), usage = TokenUsage(inputTokens = 3)),
            event("real-zero", at = epoch("2026-11-25T09:10:00Z"), usage = TokenUsage(inputTokens = 0, outputTokens = 0)),
            event("known", at = epoch("2026-11-25T10:10:00Z"), usage = TokenUsage(inputTokens = 12, outputTokens = 5)),
            event("unknown", at = epoch("2026-11-25T10:20:00Z")),
        )
        val buckets = UsageAnalytics.trend(events, UsageRange.TODAY, now, ZoneId.of("UTC"))
        assertEquals(0L, buckets[0].totalTokens)
        assertEquals(0, buckets[0].requestCount)
        assertNull(buckets[8].totalTokens)
        assertEquals(1, buckets[8].usageReportedCount)
        assertEquals(0, buckets[8].totalTokensReportedCount)
        assertEquals(0L, buckets[9].totalTokens)
        assertEquals(1, buckets[9].totalTokensReportedCount)
        assertEquals(17L, buckets[10].totalTokens)
        assertEquals(2, buckets[10].requestCount)
        assertEquals(1, buckets[10].totalTokensReportedCount)
        assertTrue(UsageAnalytics.trend(emptyList(), UsageRange.ALL, now, ZoneId.of("UTC")).isEmpty())
    }

    @Test fun allTimeTrendKeepsOldRecordsWhileCombiningLongSpansIntoReadableBuckets() {
        val zone = ZoneId.of("UTC")
        val events = listOf(
            event("first", at = epoch("2025-12-25T00:00:00Z")),
            event("boundary", at = epoch("2025-12-27T00:00:00Z")),
            event("last", at = epoch("2026-01-26T23:59:59.999Z")),
        )
        val buckets = UsageAnalytics.trend(events, UsageRange.ALL, epoch("2026-11-25T12:00:00Z"), zone)
        assertEquals(17, buckets.size)
        assertEquals(epoch("2025-12-25T00:00:00Z"), buckets.first().startEpochMs)
        assertEquals(epoch("2026-01-27T00:00:00Z"), buckets.last().endEpochMs)
        assertEquals("2025-12-25", buckets.first().label)
        assertEquals(2 * 86_400_000L, buckets.first().endEpochMs - buckets.first().startEpochMs)
        assertEquals(1, buckets.first().requestCount)
        assertEquals(1, buckets[1].requestCount)
        assertEquals(1, buckets.last().requestCount)
        assertEquals(3, buckets.sumOf { it.requestCount })
        assertTrue(buckets.zipWithNext().all { (left, right) -> left.endEpochMs == right.startEpochMs })
        val single = UsageAnalytics.trend(listOf(events.last()), UsageRange.ALL, zoneId = zone)
        assertEquals(1, single.size)
        assertEquals(1, single.single().requestCount)
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
        durationMs: Long = 100,
    ) = RequestEvent(model, provider, 200, durationMs, "/v1/responses", id = id,
        startedAtEpochMs = at, providerName = name, upstreamModel = upstream, usage = usage, outcome = outcome)

    private fun epoch(text: String) = Instant.parse(text).toEpochMilli()
}
