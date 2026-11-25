package dev.pathpilot.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pathpilot.core.RequestEvent
import dev.pathpilot.core.RequestOutcome
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
