package dev.pathpilot.app

import dev.pathpilot.app.ui.i18n.tokenCount
import androidx.compose.ui.platform.LocalResources
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun StatisticsMetrics(summary: UsageSummary) {
    val strings = LocalResources.current
    var tokensOpen by remember { mutableStateOf(false) }
    val successRate = summary.successRate?.let {
        NumberFormat.getPercentInstance().apply { maximumFractionDigits = 1 }.format(it)
    }
    val metrics = listOf(
        Triple(strings.getString(R.string.ui_request_count), strings.tokenCount(summary.requestCount.toLong()), strings.tokenCount(summary.requestCount.toLong())),
        Triple("Token", compactCount(summary.totalTokens), strings.tokenCount(summary.totalTokens)),
        Triple(strings.getString(R.string.ui_success_rate), successRate ?: "—", successRate ?: strings.getString(R.string.ui_no_request_samples)),
        Triple(strings.getString(R.string.ui_average_duration), summary.averageDurationMs?.let(::formatRequestDuration) ?: "—",
            summary.averageDurationMs?.let(::formatRequestDuration) ?: strings.getString(R.string.ui_no_request_samples)),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val columns = if (LocalDensity.current.fontScale >= 1.3f) 1 else 2
        metrics.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (label, value, spoken) ->
                    val content: @Composable ColumnScope.() -> Unit = {
                        Column(Modifier.fillMaxWidth().padding(16.dp).clearAndSetSemantics {
                            contentDescription = strings.getString(R.string.ui_metric_description, label, spoken)
                        }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"))
                        }
                    }
                    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    if (label == "Token") Card(onClick = { tokensOpen = true }, modifier = Modifier.weight(1f), colors = colors, content = content)
                    else Card(modifier = Modifier.weight(1f), colors = colors, content = content)
                }
            }
        }
        if (summary.requestCount > 0) {
            Text(strings.getString(R.string.ui_outcome_counts, summary.successCount, summary.errorCount, summary.interruptedCount),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (summary.totalTokensReportedCount < summary.requestCount) Text(
                strings.getString(R.string.ui_total_token_coverage, summary.totalTokensReportedCount, summary.requestCount),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (tokensOpen) AlertDialog(onDismissRequest = { tokensOpen = false }, title = { Text(strings.getString(R.string.ui_token_usage)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(strings.getString(R.string.ui_total) to summary.totalTokens, strings.getString(R.string.ui_input) to summary.inputTokens, strings.getString(R.string.ui_output) to summary.outputTokens,
                    strings.getString(R.string.ui_cached_input) to summary.cachedInputTokens, strings.getString(R.string.ui_reasoning_output) to summary.reasoningOutputTokens).forEach { (label, value) ->
                    Column {
                        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(strings.tokenCount(value), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Text(strings.getString(R.string.ui_total_token_coverage, summary.totalTokensReportedCount, summary.requestCount), style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { tokensOpen = false }) { Text(strings.getString(R.string.ui_done)) } })
}

@Composable
internal fun StatisticsBreakdownCard(title: String, subtitle: String?, summary: UsageSummary, onClick: () -> Unit) {
    val strings = LocalResources.current
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(strings.getString(R.string.ui_requests_known_tokens, summary.requestCount, compactCount(summary.totalTokens)), style = MaterialTheme.typography.bodyMedium)
            Text(strings.getString(R.string.ui_success_average, summary.successCount, summary.averageDurationMs?.let(::formatRequestDuration) ?: "—"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun StatisticsTrend(buckets: List<UsageTrendBucket>, range: UsageRange) {
    val strings = LocalResources.current
    if (buckets.isEmpty()) return
    var tokens by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable(range, buckets.size) { mutableIntStateOf(buckets.indexOfLast { it.requestCount > 0 }.coerceAtLeast(0)) }
    val selectedIndex = selected.coerceIn(buckets.indices)
    val current = buckets[selectedIndex]
    val values = buckets.map { if (tokens) it.totalTokens else it.requestCount.toLong() }
    val maximum = (values.filterNotNull().maxOrNull() ?: 0L).coerceAtLeast(1).toDouble()
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.primaryContainer
    val unknown = MaterialTheme.colorScheme.outline
    val baseline = MaterialTheme.colorScheme.outlineVariant
    val label = if (tokens) "Token" else strings.getString(R.string.ui_requests)
    val selectionText = "${trendPeriod(current, range)} · ${strings.tokenCount(values[selectedIndex])} ${label}"
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(strings.getString(R.string.ui_trend), style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(strings.getString(R.string.ui_requests), "Token").forEachIndexed { index, option ->
                    SegmentedButton(selected = tokens == (index == 1), onClick = { tokens = index == 1 },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        modifier = Modifier.semantics { contentDescription = strings.getString(R.string.ui_trend_metric, option) }) { Text(option) }
                }
            }
            Canvas(Modifier.fillMaxWidth().height(100.dp).pointerInput(buckets, tokens) {
                detectTapGestures { point -> selected = (point.x / size.width * buckets.size).toInt().coerceIn(buckets.indices) }
            }.semantics { contentDescription = strings.getString(R.string.ui_trend_accessibility, label, buckets.size) }) {
                val slot = size.width / buckets.size
                val width = (slot * 0.65f).coerceAtMost(28.dp.toPx())
                drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                values.forEachIndexed { index, value ->
                    val x = slot * index + (slot - width) / 2
                    if (value == null) {
                        drawLine(unknown, Offset(x, size.height - 6.dp.toPx()), Offset(x + width, size.height - 6.dp.toPx()), 2.dp.toPx())
                    } else if (value > 0) {
                        val height = (value / maximum * (size.height - 4.dp.toPx())).toFloat().coerceAtLeast(2.dp.toPx())
                        drawRoundRect(if (index == selectedIndex) primary else secondary, Offset(x, size.height - height),
                            Size(width, height), CornerRadius(3.dp.toPx()))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(buckets.first().label, style = MaterialTheme.typography.labelSmall)
                if (buckets.size > 1) Text(buckets.last().label, style = MaterialTheme.typography.labelSmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selected = selectedIndex - 1 }, enabled = selectedIndex > 0) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, strings.getString(R.string.ui_previous_period))
                }
                Text(selectionText, Modifier.weight(1f).semantics { contentDescription = selectionText },
                    style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = { selected = selectedIndex + 1 }, enabled = selectedIndex < buckets.lastIndex) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, strings.getString(R.string.ui_next_period))
                }
            }
            if (tokens && current.totalTokensReportedCount < current.requestCount) {
                Text(strings.getString(R.string.ui_total_token_coverage, current.totalTokensReportedCount, current.requestCount),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun compactCount(value: Long?): String = when {
    value == null -> "—"
    value >= 1_000_000_000 -> String.format(Locale.getDefault(), "%.1fB", value / 1_000_000_000.0)
    value >= 1_000_000 -> String.format(Locale.getDefault(), "%.1fM", value / 1_000_000.0)
    else -> NumberFormat.getIntegerInstance().format(value)
}

private fun trendPeriod(bucket: UsageTrendBucket, range: UsageRange): String {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(bucket.startEpochMs).atZone(zone)
    val end = Instant.ofEpochMilli(bucket.endEpochMs).atZone(zone)
    if (range == UsageRange.TODAY) return "${bucket.label}–${end.format(DateTimeFormatter.ofPattern("HH:mm"))}"
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val last = end.minusNanos(1).toLocalDate()
    return if (start.toLocalDate() == last) start.format(formatter) else "${start.format(formatter)}–${last.format(formatter)}"
}
