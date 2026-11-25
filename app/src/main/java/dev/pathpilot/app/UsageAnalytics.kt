package dev.pathpilot.app

import dev.pathpilot.core.RequestEvent
import dev.pathpilot.core.RequestOutcome
import dev.pathpilot.core.TokenUsage
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

internal enum class UsageRange(val label: String) {
    TODAY("今天"), LAST_7_DAYS("7天"), ALL("全部"),
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
        )
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
