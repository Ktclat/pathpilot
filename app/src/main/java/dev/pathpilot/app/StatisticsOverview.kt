package dev.pathpilot.app

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
    var tokensOpen by remember { mutableStateOf(false) }
    val successRate = summary.successRate?.let {
        NumberFormat.getPercentInstance().apply { maximumFractionDigits = 1 }.format(it)
    }
    val metrics = listOf(
        Triple("请求数", formatTokenCount(summary.requestCount.toLong()), formatTokenCount(summary.requestCount.toLong())),
        Triple("Token", compactCount(summary.totalTokens), formatTokenCount(summary.totalTokens)),
        Triple("成功率", successRate ?: "—", successRate ?: "暂无请求"),
        Triple("平均耗时", summary.averageDurationMs?.let(::formatRequestDuration) ?: "—",
            summary.averageDurationMs?.let(::formatRequestDuration) ?: "暂无请求"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val columns = if (LocalDensity.current.fontScale >= 1.3f) 1 else 2
        metrics.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (label, value, spoken) ->
                    val content: @Composable ColumnScope.() -> Unit = {
                        Column(Modifier.fillMaxWidth().padding(16.dp).clearAndSetSemantics {
                            contentDescription = "$label，$spoken"
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
            Text("成功 ${summary.successCount} · 失败 ${summary.errorCount} · 中断 ${summary.interruptedCount}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (summary.totalTokensReportedCount < summary.requestCount) Text(
                "${summary.totalTokensReportedCount}/${summary.requestCount} 条请求返回总 Token",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (tokensOpen) AlertDialog(onDismissRequest = { tokensOpen = false }, title = { Text("Token 用量") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("合计" to summary.totalTokens, "输入" to summary.inputTokens, "输出" to summary.outputTokens,
                    "缓存输入" to summary.cachedInputTokens, "推理输出" to summary.reasoningOutputTokens).forEach { (label, value) ->
                    Column {
                        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatTokenCount(value), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Text("${summary.totalTokensReportedCount}/${summary.requestCount} 条请求返回总 Token", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { tokensOpen = false }) { Text("完成") } })
}

@Composable
internal fun StatisticsBreakdownCard(title: String, subtitle: String?, summary: UsageSummary, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${summary.requestCount} 次请求 · 已知 Token ${compactCount(summary.totalTokens)}", style = MaterialTheme.typography.bodyMedium)
            Text("${summary.successCount} 次成功 · 平均 ${summary.averageDurationMs?.let(::formatRequestDuration) ?: "—"}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun StatisticsTrend(buckets: List<UsageTrendBucket>, range: UsageRange) {
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
    val label = if (tokens) "Token" else "请求"
    val selectionText = "${trendPeriod(current, range)} · ${formatTokenCount(values[selectedIndex])} $label"
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("趋势", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("请求", "Token").forEachIndexed { index, option ->
                    SegmentedButton(selected = tokens == (index == 1), onClick = { tokens = index == 1 },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        modifier = Modifier.semantics { contentDescription = "趋势：$option" }) { Text(option) }
                }
            }
            Canvas(Modifier.fillMaxWidth().height(100.dp).pointerInput(buckets, tokens) {
                detectTapGestures { point -> selected = (point.x / size.width * buckets.size).toInt().coerceIn(buckets.indices) }
            }.semantics { contentDescription = "$label 趋势，${buckets.size} 个时间段。使用下方按钮逐项查看。" }) {
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
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "上一个时间段")
                }
                Text(selectionText, Modifier.weight(1f).semantics { contentDescription = selectionText },
                    style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = { selected = selectedIndex + 1 }, enabled = selectedIndex < buckets.lastIndex) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "下一个时间段")
                }
            }
            if (tokens && current.totalTokensReportedCount < current.requestCount) {
                Text("${current.totalTokensReportedCount}/${current.requestCount} 条请求返回总 Token",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun compactCount(value: Long?): String = when {
    value == null -> "—"
    value >= 1_000_000_000 -> String.format(Locale.getDefault(), "%.1fB", value / 1_000_000_000.0)
    value >= 1_000_000 -> String.format(Locale.getDefault(), "%.1fM", value / 1_000_000.0)
    else -> formatTokenCount(value)
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
