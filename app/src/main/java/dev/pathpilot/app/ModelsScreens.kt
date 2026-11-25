package dev.pathpilot.app

import androidx.compose.ui.platform.LocalResources
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
import dev.pathpilot.core.RequestEvent
import java.text.NumberFormat

@Composable
internal fun ProviderModelGroupsScreen(
    config: RouterConfig,
    saving: Boolean,
    onOpen: (ProviderConfig) -> Unit,
    onAddProvider: () -> Unit,
) {
    val strings = LocalResources.current
    val modelsByProvider = remember(config.routes) { config.routes.groupBy { it.providerId } }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (config.providers.isEmpty()) item(key = "empty") {
            ModelsEmptyState(strings.getString(R.string.ui_no_providers), strings.getString(R.string.ui_add_provider), saving, onAddProvider)
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
                        Text(if (provider.enabled) strings.getString(R.string.ui_models_enabled_count, models.size, models.count { it.enabled })
                            else strings.getString(R.string.ui_models_provider_disabled, models.size),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(PathPilotIcons.Back, null, Modifier.size(20.dp).rotate(180f),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private enum class ModelFilter(val labelRes: Int) {
    ALL(R.string.ui_all), ENABLED(R.string.ui_enabled), DISABLED(R.string.ui_disabled),
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
    requestEvents: List<RequestEvent> = emptyList(),
    onViewUsage: ((RouteConfig) -> Unit)? = null,
) {
    val strings = LocalResources.current
    var query by rememberSaveable(provider.id) { mutableStateOf("") }
    var filter by rememberSaveable(provider.id) { mutableStateOf(ModelFilter.ALL) }
    val focus = LocalFocusManager.current
    val models = routes.filter { it.providerId == provider.id }
    val usage = remember(requestEvents) {
        requestEvents.groupBy { Triple(it.providerId, it.model, it.upstreamModel) }
            .mapValues { UsageAnalytics.summarize(it.value) }
    }
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
                            Text(strings.getString(R.string.ui_enable_provider), style = MaterialTheme.typography.titleMedium)
                            Text(if (provider.enabled) strings.getString(R.string.ui_models_enabled_count, models.size, models.count { it.enabled })
                                else strings.getString(R.string.ui_model_count, models.size),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = provider.enabled, enabled = !saving, onCheckedChange = onProviderToggle,
                            modifier = Modifier.semantics { contentDescription = strings.getString(R.string.ui_enable_named_provider, provider.name) })
                    }
                    if (!provider.enabled) Text(strings.getString(R.string.ui_provider_disabled_models_retained),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (models.isNotEmpty() || query.isNotBlank() || filter != ModelFilter.ALL) item(key = "model-filters") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.getString(R.string.ui_search_model_id)) }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = if (query.isNotEmpty()) { {
                        IconButton(onClick = { query = "" }) { Icon(PathPilotIcons.Close, strings.getString(R.string.ui_clear_search)) }
                    } } else null,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ModelFilter.entries.forEachIndexed { index, option ->
                        SegmentedButton(selected = filter == option, onClick = { filter = option },
                            shape = SegmentedButtonDefaults.itemShape(index, ModelFilter.entries.size)) {
                            Text(strings.getString(option.labelRes))
                        }
                    }
                }
            }
        }
        when {
            models.isEmpty() -> item(key = "empty-models") {
                ModelsEmptyState(strings.getString(R.string.ui_no_models), strings.getString(R.string.ui_add_model), saving, onAdd)
            }
            visible.isEmpty() -> item(key = "empty-results") {
                ModelsEmptyState(strings.getString(R.string.ui_no_matching_models), strings.getString(R.string.ui_clear_filters), false) {
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
                            if (model.alias != model.upstreamModel) Text(strings.getString(R.string.ui_upstream_id_value, model.upstreamModel), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (onViewUsage != null) {
                                val summary = usage[Triple(provider.id, model.alias, model.upstreamModel)]
                                TextButton(onClick = { onViewUsage(model) }, contentPadding = PaddingValues(0.dp)) {
                                    Text(if (summary == null) strings.getString(R.string.ui_view_usage) else strings.getString(R.string.ui_recent_request_prefix, summary.requestCount) + " " +
                                        (summary.totalTokens?.let { strings.getString(R.string.ui_known_tokens, NumberFormat.getIntegerInstance().format(it)) } ?: strings.getString(R.string.ui_tokens_unavailable)),
                                        style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        Switch(checked = model.enabled, enabled = !saving, onCheckedChange = { onToggle(model, it) },
                            modifier = Modifier.semantics { contentDescription = strings.getString(R.string.ui_enable_named_model, model.alias) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelsEmptyState(title: String, action: String, saving: Boolean, onAction: () -> Unit) {
    val strings = LocalResources.current
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        FilledTonalButton(enabled = !saving, onClick = onAction, shapes = ButtonDefaults.shapes()) { Text(action) }
    }
}
