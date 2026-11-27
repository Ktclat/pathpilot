package dev.pathpilot.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pathpilot.app.runtime.McpCallHistoryStore
import dev.pathpilot.app.runtime.McpHistoryError
import dev.pathpilot.app.ui.i18n.rangeLabel
import dev.pathpilot.app.ui.icons.PathPilotIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun McpCallHistoryPage(store: McpCallHistoryStore, onBack: () -> Unit) {
    val history by store.state.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var range by rememberSaveable { mutableStateOf(UsageRange.ALL) }
    var connection by rememberSaveable { mutableStateOf("") }
    var tool by rememberSaveable { mutableStateOf("") }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    val filtered = filterMcpCalls(history.events, range, connection, tool)
    val succeeded = filtered.count { it.success }

    fun changeHistory(operation: () -> Unit) {
        if (working) return
        working = true
        scope.launch {
            try { withContext(Dispatchers.IO) { operation() } }
            finally { working = false }
        }
    }

    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mcp_history)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(PathPilotIcons.Back, stringResource(R.string.ui_back)) }
                },
                actions = {
                    IconButton(enabled = !working && (history.events.isNotEmpty() || history.error == McpHistoryError.WRITE),
                        onClick = { confirmClear = true }) {
                        Icon(PathPilotIcons.Delete, stringResource(R.string.mcp_history_clear))
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UsageRange.entries.forEach { option ->
                        FilterChip(selected = range == option, onClick = { range = option },
                            label = { Text(resources.rangeLabel(option)) })
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = connection, onValueChange = { connection = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(R.string.mcp_history_filter_connection)) })
                    OutlinedTextField(value = tool, onValueChange = { tool = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(R.string.mcp_history_filter_tool)) })
                }
            }
            history.error?.let { error ->
                item {
                    Column {
                        Text(stringResource(when (error) {
                            McpHistoryError.READ -> R.string.mcp_history_read_error
                            McpHistoryError.WRITE -> R.string.mcp_history_write_error
                            McpHistoryError.CLEAR -> R.string.mcp_history_clear_error
                        }), color = MaterialTheme.colorScheme.error)
                        TextButton(enabled = !working, onClick = {
                            if (error == McpHistoryError.CLEAR) confirmClear = true else changeHistory(store::reload)
                        }) { Text(stringResource(R.string.ui_retry)) }
                    }
                }
            }
            if (history.error == null || filtered.isNotEmpty()) {
                item {
                    Text(stringResource(R.string.mcp_history_totals, filtered.size, succeeded, filtered.size - succeeded),
                        style = MaterialTheme.typography.titleMedium)
                }
                if (filtered.isEmpty()) item {
                    Text(stringResource(if (history.events.isEmpty()) R.string.mcp_history_empty else R.string.mcp_history_no_matches))
                }
            }
            items(filtered, key = { it.id }) { event -> McpCallRow(event, includeDate = true) }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.mcp_history_clear)) },
        text = { Text(stringResource(R.string.mcp_history_clear_body)) },
        confirmButton = {
            TextButton(onClick = { confirmClear = false; changeHistory(store::clear) }) {
                Text(stringResource(R.string.mcp_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.mcp_cancel)) }
        },
    )
}
