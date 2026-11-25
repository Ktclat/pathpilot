package dev.pathpilot.app

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
        ?.takeIf { it.isNotBlank() } ?: if (providerFilter != null) "所选供应商" else ""
    if (onBack != null) BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("统计") },
            navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(PathPilotIcons.Back, "返回") } },
            actions = {
                IconButton(onClick = { help = true }) { Icon(Icons.Outlined.Info, "统计说明") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "更多操作") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("清空记录") }, enabled = events.isNotEmpty() || error != null,
                            leadingIcon = { Icon(PathPilotIcons.Delete, null) },
                            onClick = { menu = false; confirmClear = true })
                    }
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("概览", "请求").forEachIndexed { index, label ->
                    SegmentedButton(selected = requestsTab == (index == 1), onClick = { requestsTab = index == 1 },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        modifier = Modifier.semantics { contentDescription = if (index == 0) "统计概览" else "查看请求记录" }) { Text(label) }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UsageRange.entries.forEach { option ->
                    FilterChip(selected = range == option, onClick = { range = option }, label = { Text(option.label) })
                }
            }
            if (providerFilter != null || modelFilter != null) {
                InputChip(selected = true, onClick = { providerFilter = null; modelFilter = null; upstreamFilter = null },
                    label = { Text(listOf(providerName, modelFilter.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
                        maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = { Icon(PathPilotIcons.Close, null, Modifier.size(18.dp)) },
                    modifier = Modifier.semantics { contentDescription = "清除模型筛选" })
            }
            if (error != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onRetry) { Text("重试") }
            }
        }
        if (requestsTab) RequestHistoryList(visible, query, { query = it }, outcome, { outcome = it }, onDetails = { details = it })
        else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "summary") { StatisticsMetrics(summary) }
            if (scoped.isEmpty()) item(key = "empty") { RequestEmptyState("暂无请求记录") }
            else {
                item(key = "trend") { StatisticsTrend(trend, range) }
                item(key = "breakdown") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("用量明细", style = MaterialTheme.typography.titleMedium)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            listOf("供应商", "模型").forEachIndexed { index, label ->
                                SegmentedButton(selected = groupByModel == (index == 1), onClick = { groupByModel = index == 1 },
                                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                                    modifier = Modifier.semantics { contentDescription = "按${label}查看统计" }) { Text(label) }
                            }
                        }
                    }
                }
                if (groupByModel) items(modelUsage, key = { "${it.key.providerId.length}:${it.key.providerId}${it.key.model.length}:${it.key.model}${it.key.upstreamModel}" }) { model ->
                    StatisticsBreakdownCard(model.key.model.ifBlank { "未指定模型" },
                        listOf(model.providerName, model.key.upstreamModel.takeIf { it != model.key.model }.orEmpty())
                            .filter { it.isNotBlank() }.joinToString(" · "), model.summary) {
                        showRequests(model.key.providerId, model.key.model, model.key.upstreamModel)
                    }
                } else items(providerUsage, key = { it.providerId }) { provider ->
                    StatisticsBreakdownCard(provider.providerName, null, provider.summary) { showRequests(provider.providerId) }
                }
            }
        }
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text("统计说明") },
        text = { Text("统计基于保留的最近 1,000 条请求，按手机本地时间分组。Token 汇总上游已返回的用量，“—”表示未提供。缓存输入属于输入 Token，推理输出属于输出 Token。成功率和平均耗时包含所选范围内的全部请求；耗时为请求总时长。订阅剩余额度不在此统计中。") },
        confirmButton = { TextButton(onClick = { help = false }) { Text("知道了") } })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清空请求记录？") },
        text = { Text("将删除已保存的请求记录及其用量统计。") },
        confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
    details?.let { RequestDetailsSheet(it, onDismiss = { details = null }) }
}

@Composable
private fun ColumnScope.RequestHistoryList(
    events: List<RequestEvent>, query: String, onQuery: (String) -> Unit,
    outcome: RequestOutcome?, onOutcome: (RequestOutcome?) -> Unit, onDetails: (RequestEvent) -> Unit,
) {
    val focus = LocalFocusManager.current
    var statusMenu by remember { mutableStateOf(false) }
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "filters") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, onQuery, Modifier.fillMaxWidth(), label = { Text("搜索模型或供应商") },
                    singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = if (query.isNotEmpty()) { {
                        IconButton(onClick = { onQuery("") }) { Icon(PathPilotIcons.Close, "清除搜索") }
                    } } else null,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        OutlinedButton(onClick = { statusMenu = true }) {
                            Text(outcome?.let(::requestOutcomeLabel) ?: "全部状态")
                            Spacer(Modifier.width(4.dp)); Icon(PathPilotIcons.ExpandMore, null, Modifier.size(18.dp))
                        }
                        DropdownMenu(statusMenu, onDismissRequest = { statusMenu = false }) {
                            DropdownMenuItem(text = { Text("全部状态") }, onClick = { onOutcome(null); statusMenu = false })
                            RequestOutcome.entries.forEach { option ->
                                DropdownMenuItem(text = { Text(requestOutcomeLabel(option)) }, onClick = { onOutcome(option); statusMenu = false })
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("${events.size} 条", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (events.isEmpty()) item(key = "empty") {
            RequestEmptyState(if (query.isBlank() && outcome == null) "暂无请求记录" else "没有匹配的请求")
        }
        items(events, key = { it.id }) { event -> RequestHistoryCard(event, onClick = { onDetails(event) }) }
    }
}

@Composable
internal fun RequestHistoryCard(event: RequestEvent, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(event.model.ifBlank { "未指定模型" }, Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(requestOutcomeLabel(event.outcome), style = MaterialTheme.typography.labelLarge,
                    color = if (event.outcome == RequestOutcome.SUCCESS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            Text(event.providerName.ifBlank { event.providerId.ifBlank { "未匹配供应商" } },
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
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("请求详情", style = MaterialTheme.typography.headlineSmall) }
            item { RequestDetailRow("共享模型", event.model.ifBlank { "未指定" }) }
            item { RequestDetailRow("供应商", event.providerName.ifBlank { event.providerId.ifBlank { "未匹配" } }) }
            if (event.upstreamModel.isNotBlank()) item { RequestDetailRow("上游模型", event.upstreamModel) }
            item { RequestDetailRow("时间", requestTime(event.startedAtEpochMs, full = true)) }
            item { RequestDetailRow("耗时", formatRequestDuration(event.durationMs)) }
            item { RequestDetailRow("接口", event.endpoint) }
            item { RequestDetailRow("返回方式", if (event.streaming) "流式" else "非流式") }
            item { RequestDetailRow("结果", requestOutcomeLabel(event.outcome)) }
            item { RequestDetailRow("HTTP 状态", event.status.toString()) }
            event.errorCode?.let { code -> item { RequestDetailRow("错误码", code) } }
            item { HorizontalDivider() }
            item { Text("Token 用量", style = MaterialTheme.typography.titleMedium) }
            item { RequestDetailRow("输入", formatTokenCount(event.usage?.inputTokens)) }
            item { RequestDetailRow("输出", formatTokenCount(event.usage?.outputTokens)) }
            item { RequestDetailRow("合计", formatTokenCount(UsageAnalytics.totalTokens(event.usage))) }
            item { RequestDetailRow("缓存输入", formatTokenCount(event.usage?.cachedInputTokens)) }
            item { RequestDetailRow("推理输出", formatTokenCount(event.usage?.reasoningOutputTokens)) }
        }
    }
}

@Composable
private fun RequestDetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.width(80.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RequestEmptyState(message: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun requestTime(epochMs: Long, full: Boolean = false): String = Instant.ofEpochMilli(epochMs)
    .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(if (full) "yyyy-MM-dd HH:mm:ss" else "MM-dd HH:mm:ss"))
