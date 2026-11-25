package dev.pathpilot.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pathpilot.app.ui.icons.PathPilotIcons
import dev.pathpilot.app.ui.icons.ProviderBrandIcon
import dev.pathpilot.core.ProviderConfig
import dev.pathpilot.core.RouteConfig
import dev.pathpilot.core.RouterConfig

@Composable
internal fun ProviderModelGroupsScreen(
    config: RouterConfig,
    saving: Boolean,
    onOpen: (ProviderConfig) -> Unit,
    onAddProvider: () -> Unit,
) {
    val modelsByProvider = remember(config.routes) { config.routes.groupBy { it.providerId } }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (config.providers.isEmpty()) item(key = "empty") {
            ModelsEmptyState("还没有供应商", "添加供应商", saving, onAddProvider)
        }
        items(config.providers, key = { "provider:${it.id}" }) { provider ->
            val models = modelsByProvider[provider.id].orEmpty()
            OutlinedCard(onClick = { onOpen(provider) }, enabled = !saving) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ProviderBrandIcon(provider.kind, Modifier.size(28.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(provider.name, style = MaterialTheme.typography.titleMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (provider.enabled) "${models.size} 个模型 · 已启用 ${models.count { it.enabled }} 个"
                            else "${models.size} 个模型 · 供应商已停用",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(PathPilotIcons.Back, null, Modifier.size(20.dp).rotate(180f),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private enum class ModelFilter(val label: String) {
    ALL("全部"), ENABLED("已启用"), DISABLED("已停用"),
}

@Composable
internal fun ProviderModelsScreen(
    provider: ProviderConfig,
    routes: List<RouteConfig>,
    saving: Boolean,
    onAdd: () -> Unit,
    onEdit: (RouteConfig) -> Unit,
    onToggle: (RouteConfig, Boolean) -> Unit,
    onProviderToggle: (Boolean) -> Unit,
) {
    var query by rememberSaveable(provider.id) { mutableStateOf("") }
    var filter by rememberSaveable(provider.id) { mutableStateOf(ModelFilter.ALL) }
    val focus = LocalFocusManager.current
    val models = routes.filter { it.providerId == provider.id }
    val needle = query.trim()
    val visible = models.filter { model ->
        (model.alias.contains(needle, ignoreCase = true) || model.upstreamModel.contains(needle, ignoreCase = true)) &&
            when (filter) {
                ModelFilter.ALL -> true
                ModelFilter.ENABLED -> model.enabled
                ModelFilter.DISABLED -> !model.enabled
            }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "provider-summary") {
            OutlinedCard {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ProviderBrandIcon(provider.kind, Modifier.size(28.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("启用供应商", style = MaterialTheme.typography.titleMedium)
                            Text(if (provider.enabled) "${models.size} 个模型 · 已启用 ${models.count { it.enabled }} 个"
                                else "${models.size} 个模型",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = provider.enabled, enabled = !saving, onCheckedChange = onProviderToggle,
                            modifier = Modifier.semantics { contentDescription = "启用供应商 ${provider.name}" })
                    }
                    if (!provider.enabled) Text("供应商已停用，模型开关设置保留",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (models.isNotEmpty() || query.isNotBlank() || filter != ModelFilter.ALL) item(key = "model-filters") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索共享模型名或上游 ID") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = if (query.isNotEmpty()) { {
                        IconButton(onClick = { query = "" }) { Icon(PathPilotIcons.Close, "清除搜索") }
                    } } else null,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ModelFilter.entries.forEachIndexed { index, option ->
                        SegmentedButton(selected = filter == option, onClick = { filter = option },
                            shape = SegmentedButtonDefaults.itemShape(index, ModelFilter.entries.size)) {
                            Text(option.label)
                        }
                    }
                }
            }
        }
        when {
            models.isEmpty() -> item(key = "empty-models") {
                ModelsEmptyState("还没有模型", "添加模型", saving, onAdd)
            }
            visible.isEmpty() -> item(key = "empty-results") {
                ModelsEmptyState("没有匹配的模型", "清除筛选", false) {
                    query = ""
                    filter = ModelFilter.ALL
                    focus.clearFocus()
                }
            }
            else -> items(visible, key = { "model:${it.alias}" }) { model ->
                OutlinedCard(onClick = { onEdit(model) }, enabled = !saving) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(model.alias, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (model.alias != model.upstreamModel) Text("上游 ID：${model.upstreamModel}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Switch(checked = model.enabled, enabled = !saving, onCheckedChange = { onToggle(model, it) },
                            modifier = Modifier.semantics { contentDescription = "启用模型 ${model.alias}" })
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelsEmptyState(title: String, action: String, saving: Boolean, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        FilledTonalButton(enabled = !saving, onClick = onAction, shapes = ButtonDefaults.shapes()) { Text(action) }
    }
}
