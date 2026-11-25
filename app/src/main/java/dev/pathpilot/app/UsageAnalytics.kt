package dev.pathpilot.app

import dev.pathpilot.core.RequestEvent
import dev.pathpilot.core.RequestOutcome
import dev.pathpilot.core.TokenUsage
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToLong

internal enum class UsageRange(val label: String) {
    TODAY("今天"), LAST_7_DAYS("7天"), LAST_30_DAYS("30天"), ALL("全部"),
}

internal data class UsageSummary(
    val requestCount: Int,
    val successCount: Int,
    val errorCount: Int,
    val interruptedCount: Int,
    val usageReportedCount: Int,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val totalTokens: Long?,
    val cachedInputTokens: Long?,
    val reasoningOutputTokens: Long?,
    val averageDurationMs: Long? = null,
    val totalTokensReportedCount: Int = 0,
) {
    val successRate: Double? get() = if (requestCount == 0) null else successCount.toDouble() / requestCount
}

internal data class ProviderUsageSummary(
    val providerId: String,
    val providerName: String,
    val summary: UsageSummary,
)

/** End is exclusive. Counts describe retained requests, including partial or unknown usage. */
internal data class UsageTrendBucket(
    val startEpochMs: Long,
    val endEpochMs: Long,
    val label: String,
    val requestCount: Int,
    val totalTokens: Long?,
    val usageReportedCount: Int,
    val totalTokensReportedCount: Int,
)

internal data class ModelUsageKey(val providerId: String, val model: String, val upstreamModel: String)

internal data class ModelUsageSummary(
    val key: ModelUsageKey,
    val providerName: String,
    val summary: UsageSummary,
)

/** Statistics describe retained requests; missing upstream counters stay unknown. */
internal object UsageAnalytics {
    fun filter(
        events: List<RequestEvent>,
        range: UsageRange = UsageRange.ALL,
        query: String = "",
        outcome: RequestOutcome? = null,
        providerId: String? = null,
        model: String? = null,
        upstreamModel: String? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<RequestEvent> {
        val today = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId).toLocalDate()
        val from = when (range) {
            UsageRange.TODAY -> today.atStartOfDay(zoneId).toInstant().toEpochMilli()
            UsageRange.LAST_7_DAYS -> today.minusDays(6).atStartOfDay(zoneId).toInstant().toEpochMilli()
            UsageRange.LAST_30_DAYS -> today.minusDays(29).atStartOfDay(zoneId).toInstant().toEpochMilli()
            UsageRange.ALL -> Long.MIN_VALUE
        }
        val until = if (range == UsageRange.ALL) Long.MAX_VALUE
            else today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val needle = query.trim()
        return events.asSequence().filter { event ->
            event.startedAtEpochMs >= from && event.startedAtEpochMs < until &&
                (outcome == null || event.outcome == outcome) &&
                (providerId == null || event.providerId == providerId) &&
                (model == null || event.model == model) &&
                (upstreamModel == null || event.upstreamModel == upstreamModel) &&
                (needle.isEmpty() || event.model.contains(needle, true) ||
                    event.upstreamModel.contains(needle, true) || event.providerName.contains(needle, true))
        }.sortedByDescending { it.startedAtEpochMs }.toList()
    }

    fun summarize(events: List<RequestEvent>): UsageSummary {
        val usage = events.mapNotNull { it.usage }
        return UsageSummary(
            requestCount = events.size,
            totalTokensReportedCount = usage.count { totalTokens(it) != null },
            successCount = events.count { it.outcome == RequestOutcome.SUCCESS },
            errorCount = events.count { it.outcome == RequestOutcome.HTTP_ERROR },
            interruptedCount = events.count { it.outcome == RequestOutcome.INTERRUPTED },
            usageReportedCount = usage.count { value ->
                listOf(value.inputTokens, value.outputTokens, value.totalTokens,
                    value.cachedInputTokens, value.reasoningOutputTokens).any { it != null }
            },
            inputTokens = usage.mapNotNull { it.inputTokens }.sumOrUnknown(),
            outputTokens = usage.mapNotNull { it.outputTokens }.sumOrUnknown(),
            totalTokens = usage.mapNotNull(::totalTokens).sumOrUnknown(),
            cachedInputTokens = usage.mapNotNull { it.cachedInputTokens }.sumOrUnknown(),
            reasoningOutputTokens = usage.mapNotNull { it.reasoningOutputTokens }.sumOrUnknown(),
            averageDurationMs = if (events.isEmpty()) null else events.map { it.durationMs }.average().roundToLong(),
        )
    }

    fun byProvider(events: List<RequestEvent>): List<ProviderUsageSummary> = events
        .groupBy { it.providerId }
        .map { (providerId, requests) ->
            val latest = requests.maxBy { it.startedAtEpochMs }
            ProviderUsageSummary(providerId, latest.providerName.ifBlank { "未匹配供应商" }, summarize(requests))
        }
        .sortedWith(compareByDescending<ProviderUsageSummary> { it.summary.requestCount }
            .thenBy { it.providerName }.thenBy { it.providerId })

    fun trend(
        events: List<RequestEvent>,
        range: UsageRange,
        nowEpochMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<UsageTrendBucket> {
        val scoped = filter(events, range = range, nowEpochMs = nowEpochMs, zoneId = zoneId)
        val today = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId).toLocalDate()
        if (range == UsageRange.TODAY) {
            val start = today.atStartOfDay(zoneId)
            val end = today.plusDays(1).atStartOfDay(zoneId)
            // ZonedDateTime.plusHours follows elapsed time, preserving both repeated DST hours.
            val hours = generateSequence(start) { it.plusHours(1) }.takeWhile { it.isBefore(end) }.toList()
            val labels = hours.map { it.format(DateTimeFormatter.ofPattern("HH:mm")) }
            val duplicates = labels.groupingBy { it }.eachCount()
            return hours.mapIndexed { index, hour ->
                val label = labels[index].let { if (duplicates.getValue(it) > 1) "$it ${hour.offset}" else it }
                bucket(scoped, hour.toInstant().toEpochMilli(), minOf(hour.plusHours(1), end).toInstant().toEpochMilli(), label)
            }
        }
        if (range == UsageRange.ALL && scoped.isEmpty()) return emptyList()
        val from = when (range) {
            UsageRange.LAST_7_DAYS -> today.minusDays(6)
            UsageRange.LAST_30_DAYS -> today.minusDays(29)
            UsageRange.ALL -> Instant.ofEpochMilli(scoped.last().startedAtEpochMs).atZone(zoneId).toLocalDate()
            UsageRange.TODAY -> today
        }
        val until = if (range == UsageRange.ALL)
            Instant.ofEpochMilli(scoped.first().startedAtEpochMs).atZone(zoneId).toLocalDate().plusDays(1)
            else today.plusDays(1)
        val days = ChronoUnit.DAYS.between(from, until)
        // Keep the full retained date span readable on a phone instead of truncating old data.
        val stepDays = if (range == UsageRange.ALL) (days + 29) / 30 else 1L
        val formatter = DateTimeFormatter.ofPattern(if (from.year != until.minusDays(1).year) "yyyy-MM-dd" else "MM-dd")
        return generateSequence(from) { it.plusDays(stepDays) }.takeWhile { it.isBefore(until) }.map { date ->
            val end = minOf(date.plusDays(stepDays), until)
            bucket(scoped, date.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                end.atStartOfDay(zoneId).toInstant().toEpochMilli(), date.format(formatter))
        }.toList()
    }

    private fun bucket(events: List<RequestEvent>, from: Long, until: Long, label: String): UsageTrendBucket {
        val requests = events.filter { it.startedAtEpochMs >= from && it.startedAtEpochMs < until }
        val summary = summarize(requests)
        return UsageTrendBucket(from, until, label, requests.size,
            if (requests.isEmpty()) 0L else summary.totalTokens,
            summary.usageReportedCount, requests.count { totalTokens(it.usage) != null })
    }

    fun byModel(events: List<RequestEvent>): List<ModelUsageSummary> = events
        .groupBy { ModelUsageKey(it.providerId, it.model, it.upstreamModel) }
        .map { (key, requests) ->
            val latest = requests.maxBy { it.startedAtEpochMs }
            ModelUsageSummary(key, latest.providerName.ifBlank { latest.providerId }, summarize(requests))
        }
        .sortedWith(compareByDescending<ModelUsageSummary> { it.summary.requestCount }
            .thenBy { it.providerName }.thenBy { it.key.model }.thenBy { it.key.upstreamModel })

    fun totalTokens(usage: TokenUsage?): Long? {
        usage?.totalTokens?.let { return it }
        val input = usage?.inputTokens ?: return null
        val output = usage.outputTokens ?: return null
        return if (input <= Long.MAX_VALUE - output) input + output else null
    }

    private fun List<Long>.sumOrUnknown(): Long? {
        if (isEmpty()) return null
        var total = 0L
        for (value in this) {
            if (value > Long.MAX_VALUE - total) return null
            total += value
        }
        return total
    }
}

internal fun formatTokenCount(value: Long?): String = value?.let { NumberFormat.getIntegerInstance().format(it) } ?: "未提供"

internal fun formatRequestDuration(durationMs: Long): String = if (durationMs < 1_000) "$durationMs ms"
    else String.format(Locale.getDefault(), "%.1f s", durationMs / 1_000.0)

internal fun requestOutcomeLabel(outcome: RequestOutcome): String = when (outcome) {
    RequestOutcome.SUCCESS -> "成功"
    RequestOutcome.HTTP_ERROR -> "失败"
    RequestOutcome.INTERRUPTED -> "中断"
}
