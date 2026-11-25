package dev.pathpilot.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pathpilot.app.ui.icons.PathPilotIcons
import dev.pathpilot.core.RequestEvent
import dev.pathpilot.core.RequestOutcome
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

@Composable
internal fun UsageOverviewCard(events: List<RequestEvent>, error: String?, onClick: () -> Unit) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(60_000); value = System.currentTimeMillis() }
    }
    val summary = remember(events, now) {
        UsageAnalytics.summarize(UsageAnalytics.filter(events, range = UsageRange.TODAY, nowEpochMs = now))
    }
    OutlinedCard(onClick = onClick) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(PathPilotIcons.Usage, null)
                Text("今日用量", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Icon(PathPilotIcons.OpenInNew, "查看用量与请求", Modifier.size(20.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(NumberFormat.getIntegerInstance().format(summary.requestCount), style = MaterialTheme.typography.headlineSmall)
                    Text("请求", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.weight(1f)) {
                    Text(summary.totalTokens?.let { NumberFormat.getIntegerInstance().format(it) } ?: "未提供",
                        style = MaterialTheme.typography.headlineSmall)
                    Text("已知 Token", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
internal fun RecentRequestPreview(event: RequestEvent, onClick: () -> Unit) {
    val time = remember(event.startedAtEpochMs) {
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(event.startedAtEpochMs))
    }
    val outcome = when (event.outcome) {
        RequestOutcome.SUCCESS -> "成功"
        RequestOutcome.HTTP_ERROR -> "失败 · ${event.status}"
        RequestOutcome.INTERRUPTED -> "中断"
    }
    OutlinedCard(onClick = onClick) {
        ListItem(
            content = { Text(event.model.ifBlank { "未指定模型" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text("${event.providerName.ifBlank { "未匹配供应商" }} · $time", maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            trailingContent = {
                Column(horizontalAlignment = Alignment.End) {
                    Text(outcome, color = if (event.outcome == RequestOutcome.SUCCESS) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                    Text("${event.durationMs} ms", style = MaterialTheme.typography.labelSmall)
                }
            },
        )
    }
}
