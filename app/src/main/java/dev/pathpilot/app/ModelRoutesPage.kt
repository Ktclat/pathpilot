package dev.pathpilot.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.style.TextOverflow
import dev.pathpilot.app.ui.icons.PathPilotIcons
import dev.pathpilot.core.ProviderConfig
import dev.pathpilot.core.ProviderKind
import dev.pathpilot.core.RequestEvent
import dev.pathpilot.core.RouteConfig
import dev.pathpilot.core.RouterConfig

/** Providers form the first level; each provider owns its configuration and shared models. */
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
    authenticatedProviders: Set<String> = emptySet(),
    onEditProvider: ((ProviderConfig) -> Unit)? = null,
    onLogin: ((ProviderConfig) -> Unit)? = null,
    onFetchModels: ((ProviderConfig) -> Unit)? = null,
    onStatistics: (() -> Unit)? = null,
    requestEvents: List<RequestEvent> = emptyList(),
    onViewUsage: ((ProviderConfig, RouteConfig?) -> Unit)? = null,
) {
    val strings = LocalResources.current
    val provider = config.providers.find { it.id == selectedProviderId }
    val pages = rememberSaveableStateHolder()
    var menu by remember(provider?.id) { mutableStateOf(false) }
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
                if (provider == null) {
                    IconButton(enabled = !saving, onClick = onAddProvider) {
                        Icon(PathPilotIcons.Add, strings.getString(R.string.ui_add_provider))
                    }
                    onStatistics?.let { action -> IconButton(onClick = action) {
                        Icon(PathPilotIcons.Usage, strings.getString(R.string.ui_statistics))
                    } }
                } else {
                    onEditProvider?.let { edit -> IconButton(enabled = !saving, onClick = { edit(provider) }) {
                        Icon(PathPilotIcons.Edit, strings.getString(R.string.ui_edit_provider))
                    } }
                    IconButton(enabled = !saving, onClick = { onAddModel(provider.id) }) {
                        Icon(PathPilotIcons.Add, strings.getString(R.string.ui_add_model))
                    }
                    val canSignInAgain = provider.kind == ProviderKind.CODEX && provider.id in authenticatedProviders && onLogin != null
                    if (onViewUsage != null || canSignInAgain) Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, strings.getString(R.string.ui_more_actions)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            onViewUsage?.let { usage -> DropdownMenuItem(
                                text = { Text(strings.getString(R.string.ui_view_provider_usage)) },
                                leadingIcon = { Icon(PathPilotIcons.Usage, null) },
                                onClick = { menu = false; usage(provider, null) },
                            ) }
                            if (canSignInAgain) DropdownMenuItem(
                                text = { Text(strings.getString(R.string.ui_sign_in_again)) },
                                enabled = !saving,
                                leadingIcon = { Icon(PathPilotIcons.Account, null) },
                                onClick = { menu = false; onLogin?.invoke(provider) },
                            )
                        }
                    }
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        Box(Modifier.weight(1f)) {
            pages.SaveableStateProvider(provider?.let { "provider:${it.id}" } ?: "providers") {
                if (provider == null) ProviderModelGroupsScreen(config, saving,
                    authenticatedProviders = authenticatedProviders,
                    onOpen = { onSelectProvider(it.id) }, onAddProvider = onAddProvider,
                    onProviderToggle = onProviderToggle)
                else ProviderModelsScreen(provider, config.routes, saving,
                    signedIn = provider.id in authenticatedProviders,
                    onAdd = { onAddModel(provider.id) }, onEdit = onEditModel, onToggle = onModelToggle,
                    onProviderToggle = { onProviderToggle(provider, it) },
                    onFetchModels = onFetchModels?.let { fetch -> { fetch(provider) } },
                    onLogin = onLogin?.let { login -> { login(provider) } },
                    requestEvents = requestEvents,
                    onViewUsage = onViewUsage?.let { view -> { route -> view(provider, route) } })
            }
        }
    }
}
