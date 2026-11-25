package dev.pathpilot.app

import dev.pathpilot.app.ui.i18n.localize
import dev.pathpilot.app.ui.i18n.tokenCount
import dev.pathpilot.app.ui.i18n.outcomeLabel
import dev.pathpilot.app.ui.i18n.rangeLabel
import androidx.compose.ui.platform.LocalResources
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pathpilot.app.ui.icons.PathPilotIcons
import dev.pathpilot.core.RequestEvent
import dev.pathpilot.core.RequestOutcome
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatisticsPage(
    events: List<RequestEvent>,
    error: String?,
    initialProviderId: String? = null,
    initialModel: String? = null,
    initialUpstreamModel: String? = null,
    initialRequests: Boolean = false,
    initialRequestId: String? = null,
    onBack: (() -> Unit)? = null,
    onClear: () -> Unit,
    onRetry: () -> Unit,
) {
    val strings = LocalResources.current
    var requestsTab by rememberSaveable(initialProviderId, initialModel, initialRequests, initialRequestId) {
        mutableStateOf(initialRequests || initialModel != null || initialRequestId != null)
    }
    var range by rememberSaveable(initialProviderId, initialModel, initialUpstreamModel, initialRequests, initialRequestId) {
        mutableStateOf(if (initialRequests || initialRequestId != null || initialProviderId != null || initialModel != null)
            UsageRange.ALL else UsageRange.TODAY)
    }
    var query by rememberSaveable { mutableStateOf("") }
    var outcome by rememberSaveable { mutableStateOf<RequestOutcome?>(null) }
    var providerFilter by rememberSaveable(initialProviderId, initialModel) { mutableStateOf(initialProviderId) }
    var modelFilter by rememberSaveable(initialProviderId, initialModel) { mutableStateOf(initialModel) }
    var upstreamFilter by rememberSaveable(initialProviderId, initialModel, initialUpstreamModel) { mutableStateOf(initialUpstreamModel) }
    var details by remember(initialRequestId) { mutableStateOf(events.firstOrNull { it.id == initialRequestId }) }
    var menu by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    // An open statistics page also advances to the next local day without a new request.
    val now by produceState(System.currentTimeMillis()) {
        while (isActive) { delay(60_000); value = System.currentTimeMillis() }
    }
    val scoped = remember(events, range, providerFilter, modelFilter, upstreamFilter, now) {
        UsageAnalytics.filter(events, range, providerId = providerFilter, model = modelFilter,
            upstreamModel = upstreamFilter, nowEpochMs = now)
    }
    val visible = remember(scoped, query, outcome) { UsageAnalytics.filter(scoped, query = query, outcome = outcome) }
    val summary = remember(scoped) { UsageAnalytics.summarize(scoped) }
    val modelUsage = remember(scoped) { UsageAnalytics.byModel(scoped) }
    val providerUsage = remember(scoped) { UsageAnalytics.byProvider(scoped) }
    val trend = remember(scoped, range, now) {
        UsageAnalytics.trend(scoped, range, nowEpochMs = now).filter { it.startEpochMs <= now }
    }
    var groupByModel by rememberSaveable { mutableStateOf(false) }
    fun showRequests(provider: String, model: String? = null, upstream: String? = null) {
        providerFilter = provider
        modelFilter = model
        upstreamFilter = upstream
        query = ""
        outcome = null
        requestsTab = true
    }
    val providerName = events.firstOrNull { it.providerId == providerFilter }?.providerName
        ?.takeIf { it.isNotBlank() } ?: if (providerFilter != null) strings.getString(R.string.ui_selected_provider) else ""
    if (onBack != null) BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(strings.getString(R.string.ui_statistics)) },
            navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(PathPilotIcons.Back, strings.getString(R.string.ui_back)) } },
            actions = {
                IconButton(onClick = { help = true }) { Icon(Icons.Outlined.Info, strings.getString(R.string.ui_statistics_help)) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, strings.getString(R.string.ui_more_actions)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(strings.getString(R.string.ui_clear_history)) }, enabled = events.isNotEmpty() || error != null,
                            leadingIcon = { Icon(PathPilotIcons.Delete, null) },
                            onClick = { menu = false; confirmClear = true })
                    }
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(strings.getString(R.string.ui_overview), strings.getString(R.string.ui_requests)).forEachIndexed { index, label ->
                    SegmentedButton(selected = requestsTab == (index == 1), onClick = { requestsTab = index == 1 },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        modifier = Modifier.semantics { contentDescription = if (index == 0) strings.getString(R.string.ui_statistics_overview) else strings.getString(R.string.ui_view_requests) }) { Text(label) }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UsageRange.entries.forEach { option ->
                    FilterChip(selected = range == option, onClick = { range = option }, label = { Text(strings.rangeLabel(option)) })
                }
            }
            if (providerFilter != null || modelFilter != null) {
                InputChip(selected = true, onClick = { providerFilter = null; modelFilter = null; upstreamFilter = null },
                    label = { Text(listOf(providerName, modelFilter.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
                        maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = { Icon(PathPilotIcons.Close, null, Modifier.size(18.dp)) },
                    modifier = Modifier.semantics { contentDescription = strings.getString(R.string.ui_clear_model_filter) })
            }
            if (error != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings.localize(error), Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onRetry) { Text(strings.getString(R.string.ui_retry)) }
            }
        }
        if (requestsTab) RequestHistoryList(visible, query, { query = it }, outcome, { outcome = it }, onDetails = { details = it })
        else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "summary") { StatisticsMetrics(summary) }
            if (scoped.isEmpty()) item(key = "empty") { RequestEmptyState(strings.getString(R.string.ui_no_requests)) }
            else {
                item(key = "trend") { StatisticsTrend(trend, range) }
                item(key = "breakdown") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(strings.getString(R.string.ui_usage_breakdown), style = MaterialTheme.typography.titleMedium)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            listOf(strings.getString(R.string.ui_providers), strings.getString(R.string.ui_models)).forEachIndexed { index, label ->
                                SegmentedButton(selected = groupByModel == (index == 1), onClick = { groupByModel = index == 1 },
                                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                                    modifier = Modifier.semantics { contentDescription = strings.getString(R.string.ui_statistics_by, label) }) { Text(label) }
                            }
                        }
                    }
                }
                if (groupByModel) items(modelUsage, key = { "${it.key.providerId.length}:${it.key.providerId}${it.key.model.length}:${it.key.model}${it.key.upstreamModel}" }) { model ->
                    StatisticsBreakdownCard(model.key.model.ifBlank { strings.getString(R.string.ui_unspecified_model) },
                        listOf(model.providerName, model.key.upstreamModel.takeIf { it != model.key.model }.orEmpty())
                            .filter { it.isNotBlank() }.joinToString(" · "), model.summary) {
                        showRequests(model.key.providerId, model.key.model, model.key.upstreamModel)
                    }
                } else items(providerUsage, key = { it.providerId }) { provider ->
                    StatisticsBreakdownCard(provider.providerName.ifBlank { strings.getString(R.string.ui_unmatched_provider) }, null, provider.summary) { showRequests(provider.providerId) }
                }
            }
        }
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text(strings.getString(R.string.ui_statistics_help)) },
        text = { Text(strings.getString(R.string.ui_statistics_help_text)) },
        confirmButton = { TextButton(onClick = { help = false }) { Text(strings.getString(R.string.ui_got_it)) } })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text(strings.getString(R.string.ui_clear_history_question)) },
        text = { Text(strings.getString(R.string.ui_clear_history_effect)) },
        confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text(strings.getString(R.string.ui_clear)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(strings.getString(R.string.ui_cancel)) } })
    details?.let { RequestDetailsSheet(it, onDismiss = { details = null }) }
}

@Composable
private fun ColumnScope.RequestHistoryList(
    events: List<RequestEvent>, query: String, onQuery: (String) -> Unit,
    outcome: RequestOutcome?, onOutcome: (RequestOutcome?) -> Unit, onDetails: (RequestEvent) -> Unit,
) {
    val strings = LocalResources.current
    val focus = LocalFocusManager.current
    var statusMenu by remember { mutableStateOf(false) }
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "filters") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, onQuery, Modifier.fillMaxWidth(), label = { Text(strings.getString(R.string.ui_search_requests)) },
                    singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = if (query.isNotEmpty()) { {
                        IconButton(onClick = { onQuery("") }) { Icon(PathPilotIcons.Close, strings.getString(R.string.ui_clear_search)) }
                    } } else null,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        OutlinedButton(onClick = { statusMenu = true }) {
                            Text(outcome?.let(strings::outcomeLabel) ?: strings.getString(R.string.ui_all_outcomes))
                            Spacer(Modifier.width(4.dp)); Icon(PathPilotIcons.ExpandMore, null, Modifier.size(18.dp))
                        }
                        DropdownMenu(statusMenu, onDismissRequest = { statusMenu = false }) {
                            DropdownMenuItem(text = { Text(strings.getString(R.string.ui_all_outcomes)) }, onClick = { onOutcome(null); statusMenu = false })
                            RequestOutcome.entries.forEach { option ->
                                DropdownMenuItem(text = { Text(strings.outcomeLabel(option)) }, onClick = { onOutcome(option); statusMenu = false })
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(strings.getString(R.string.ui_record_count, events.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (events.isEmpty()) item(key = "empty") {
            RequestEmptyState(if (query.isBlank() && outcome == null) strings.getString(R.string.ui_no_requests) else strings.getString(R.string.ui_no_matching_requests))
        }
        items(events, key = { it.id }) { event -> RequestHistoryCard(event, onClick = { onDetails(event) }) }
    }
}

@Composable
internal fun RequestHistoryCard(event: RequestEvent, onClick: () -> Unit) {
    val strings = LocalResources.current
    OutlinedCard(onClick = onClick) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(event.model.ifBlank { strings.getString(R.string.ui_unspecified_model) }, Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(strings.outcomeLabel(event.outcome), style = MaterialTheme.typography.labelLarge,
                    color = if (event.outcome == RequestOutcome.SUCCESS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            Text(event.providerName.ifBlank { event.providerId.ifBlank { strings.getString(R.string.ui_unmatched_provider) } },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(requestTime(event.startedAtEpochMs), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatRequestDuration(event.durationMs), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RequestDetailsSheet(event: RequestEvent, onDismiss: () -> Unit) {
    val strings = LocalResources.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(strings.getString(R.string.ui_request_details), style = MaterialTheme.typography.headlineSmall) }
            item { RequestDetailRow(strings.getString(R.string.ui_shared_model), event.model.ifBlank { strings.getString(R.string.ui_unspecified) }) }
            item { RequestDetailRow(strings.getString(R.string.ui_providers), event.providerName.ifBlank { event.providerId.ifBlank { strings.getString(R.string.ui_unmatched) } }) }
            if (event.upstreamModel.isNotBlank()) item { RequestDetailRow(strings.getString(R.string.ui_upstream_model), event.upstreamModel) }
            item { RequestDetailRow(strings.getString(R.string.ui_time), requestTime(event.startedAtEpochMs, full = true)) }
            item { RequestDetailRow(strings.getString(R.string.ui_duration), formatRequestDuration(event.durationMs)) }
            item { RequestDetailRow(strings.getString(R.string.ui_endpoint), event.endpoint) }
            item { RequestDetailRow(strings.getString(R.string.ui_response_mode), if (event.streaming) strings.getString(R.string.ui_streaming) else strings.getString(R.string.ui_non_streaming)) }
            item { RequestDetailRow(strings.getString(R.string.ui_outcome), strings.outcomeLabel(event.outcome)) }
            item { RequestDetailRow(strings.getString(R.string.ui_http_status), event.status.toString()) }
            event.errorCode?.let { code -> item { RequestDetailRow(strings.getString(R.string.ui_error_code), code) } }
            item { HorizontalDivider() }
            item { Text(strings.getString(R.string.ui_token_usage), style = MaterialTheme.typography.titleMedium) }
            item { RequestDetailRow(strings.getString(R.string.ui_input), strings.tokenCount(event.usage?.inputTokens)) }
            item { RequestDetailRow(strings.getString(R.string.ui_output), strings.tokenCount(event.usage?.outputTokens)) }
            item { RequestDetailRow(strings.getString(R.string.ui_total), strings.tokenCount(UsageAnalytics.totalTokens(event.usage))) }
            item { RequestDetailRow(strings.getString(R.string.ui_cached_input), strings.tokenCount(event.usage?.cachedInputTokens)) }
            item { RequestDetailRow(strings.getString(R.string.ui_reasoning_output), strings.tokenCount(event.usage?.reasoningOutputTokens)) }
        }
    }
}

@Composable
private fun RequestDetailRow(label: String, value: String) {
    val strings = LocalResources.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.width(80.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RequestEmptyState(message: String) {
    val strings = LocalResources.current
    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun requestTime(epochMs: Long, full: Boolean = false): String = Instant.ofEpochMilli(epochMs)
    .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(if (full) "yyyy-MM-dd HH:mm:ss" else "MM-dd HH:mm:ss"))
