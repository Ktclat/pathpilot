package dev.pathpilot.app

import androidx.compose.ui.platform.LocalResources
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
    val strings = LocalResources.current
    val time = remember(event.startedAtEpochMs) {
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(event.startedAtEpochMs))
    }
    val outcome = when (event.outcome) {
        RequestOutcome.SUCCESS -> strings.getString(R.string.ui_success)
        RequestOutcome.HTTP_ERROR -> strings.getString(R.string.ui_failed_status, event.status)
        RequestOutcome.INTERRUPTED -> strings.getString(R.string.ui_interrupted)
    }
    OutlinedCard(onClick = onClick) {
        ListItem(
            content = { Text(event.model.ifBlank { strings.getString(R.string.ui_unspecified_model) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text("${event.providerName.ifBlank { strings.getString(R.string.ui_unmatched_provider) }} · ${time}", maxLines = 2, overflow = TextOverflow.Ellipsis)
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
