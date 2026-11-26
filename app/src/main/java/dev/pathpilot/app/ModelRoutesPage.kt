package dev.pathpilot.app

import androidx.compose.ui.platform.LocalResources
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import dev.pathpilot.app.ui.icons.PathPilotIcons
import dev.pathpilot.app.ui.components.SettingsAction
import dev.pathpilot.core.ProviderConfig
import dev.pathpilot.core.RouteConfig
import dev.pathpilot.core.RouterConfig
import dev.pathpilot.core.RequestEvent

/** The selected provider is hoisted so the supplier page can open its model list directly. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ModelRoutesPage(
    config: RouterConfig,
    saving: Boolean,
    selectedProviderId: String?,
    onSelectProvider: (String?) -> Unit,
    onAddProvider: () -> Unit,
    onAddModel: (String) -> Unit,
    onEditModel: (RouteConfig) -> Unit,
    onModelToggle: (RouteConfig, Boolean) -> Unit,
    onProviderToggle: (ProviderConfig, Boolean) -> Unit,
    requestEvents: List<RequestEvent> = emptyList(),
    onViewUsage: ((ProviderConfig, RouteConfig?) -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
) {
    val strings = LocalResources.current
    val provider = config.providers.find { it.id == selectedProviderId }
    LaunchedEffect(selectedProviderId, provider?.id) {
        if (selectedProviderId != null && provider == null) onSelectProvider(null)
    }
    BackHandler(enabled = provider != null) { onSelectProvider(null) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(provider?.name ?: strings.getString(R.string.ui_models), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                if (provider != null) IconButton(onClick = { onSelectProvider(null) }) {
                    Icon(PathPilotIcons.Back, strings.getString(R.string.ui_back_to_providers))
                }
            },
            actions = {
                if (provider != null && onViewUsage != null) IconButton(onClick = { onViewUsage(provider, null) }) {
                    Icon(PathPilotIcons.Usage, strings.getString(R.string.ui_view_provider_usage))
                }
                if (provider != null) IconButton(enabled = !saving, onClick = { onAddModel(provider.id) }) {
                    Icon(PathPilotIcons.Add, strings.getString(R.string.ui_add_model))
                }
                onSettings?.let { SettingsAction(it) }
            },
            // The outer app scaffold already supplies status-bar and navigation-bar insets.
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        Box(Modifier.weight(1f)) {
            if (provider == null) ProviderModelGroupsScreen(config, saving,
                onOpen = { onSelectProvider(it.id) }, onAddProvider = onAddProvider)
            else key(provider.id) {
                ProviderModelsScreen(provider, config.routes, saving,
                    onAdd = { onAddModel(provider.id) }, onEdit = onEditModel, onToggle = onModelToggle,
                    onProviderToggle = { onProviderToggle(provider, it) },
                    requestEvents = requestEvents,
                    onViewUsage = onViewUsage?.let { view -> { route -> view(provider, route) } })
            }
        }
    }
}
