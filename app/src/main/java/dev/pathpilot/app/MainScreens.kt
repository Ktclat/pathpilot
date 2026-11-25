package dev.pathpilot.app

import dev.pathpilot.app.ui.i18n.localize
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.pathpilot.app.runtime.LocalAddressSnapshot
import dev.pathpilot.app.runtime.LocalAddressKind
import dev.pathpilot.app.runtime.LocalAddresses
import dev.pathpilot.app.runtime.RuntimeState
import dev.pathpilot.app.runtime.RequestHistoryState
import dev.pathpilot.app.ui.icons.PathPilotIcons
import dev.pathpilot.app.ui.icons.ProviderBrandIcon
import dev.pathpilot.core.*
import dev.pathpilot.core.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable internal fun SharingScreen(
    config: RouterConfig, runtime: RuntimeState, sessions: Set<String>, saving: Boolean, notificationPending: Boolean,
    onSharingChange: (Boolean) -> Unit, onProviders: () -> Unit, onRoutes: () -> Unit, onCopy: (String, String) -> Unit,
    history: RequestHistoryState, onRequests: () -> Unit, onRequest: (RequestEvent) -> Unit,
) {
    val strings = LocalResources.current
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val localSnapshot by produceState<LocalAddressSnapshot?>(null, context, lifecycle, runtime.running) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (runtime.running) awaitCancellation()
            else while (isActive) {
                value = withContext(Dispatchers.IO) { LocalAddresses.discover(context) }
                delay(5_000)
            }
        }
    }
    val snapshot = if (runtime.running || runtime.stopping)
        LocalAddressSnapshot(runtime.addresses, runtime.addressWarning) else localSnapshot
    val addressPort = if (runtime.running) runtime.port else config.port
    var onThisDevice by rememberSaveable { mutableStateOf(true) }
    val enabledProviders = config.providers.filter { it.enabled }
    val enabledRoutes = config.routes.filter { it.enabled }
    val ready = enabledRoutes.any { route -> enabledProviders.any { p ->
        p.id == route.providerId && (p.kind != ProviderKind.CODEX || p.id in sessions)
    } }
    val setupAction = when {
        config.providers.isEmpty() -> strings.getString(R.string.ui_add_provider)
        enabledProviders.isEmpty() -> strings.getString(R.string.ui_enable_provider)
        enabledRoutes.isEmpty() -> if (config.routes.isEmpty()) strings.getString(R.string.ui_add_model) else strings.getString(R.string.ui_enable_model)
        !ready -> strings.getString(R.string.ui_check_provider)
        else -> null
    }
    val checked = runtime.running || runtime.starting || notificationPending
    val canToggle = !runtime.stopping && (checked || !saving)
    val status = when {
        runtime.stopping -> strings.getString(R.string.ui_stopping)
        runtime.starting -> strings.getString(R.string.ui_starting)
        notificationPending -> strings.getString(R.string.ui_waiting_notification)
        runtime.running && !ready -> strings.getString(R.string.ui_sharing_no_models)
        runtime.running -> strings.getString(R.string.ui_on)
        else -> strings.getString(R.string.ui_off)
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = if (runtime.running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer), shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(PathPilotIcons.Share, null, Modifier.size(28.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(strings.getString(R.string.ui_model_sharing), style = MaterialTheme.typography.titleMedium)
                            Text(status, style = MaterialTheme.typography.bodyMedium)
                        }
                        Switch(checked = checked, enabled = canToggle, onCheckedChange = onSharingChange,
                            modifier = Modifier.semantics { contentDescription = strings.getString(R.string.ui_model_sharing_switch) })
                    }
                }
            }
        }
        runtime.lastError?.let { message -> item { Text(strings.localize(message), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) } }
        item { SharingAddresses(snapshot, addressPort,
            onThisDevice, { onThisDevice = it }, onCopy) }
        item { ConnectionField(strings.getString(R.string.ui_access_key), config.accessKey, secret = true, onCopy = { onCopy(strings.getString(R.string.ui_access_key), it) }) }
        if (setupAction != null) item {
            FilledTonalButton(enabled = !saving, shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight),
                modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight),
                onClick = {
                    if (config.providers.isEmpty() || enabledProviders.isEmpty() || enabledRoutes.isNotEmpty()) onProviders()
                    else onRoutes()
                }) {
                Icon(PathPilotIcons.Add, null); Spacer(Modifier.width(8.dp)); Text(setupAction)
            }
        }
        if (!onThisDevice && snapshot?.addresses?.any { it.kind == LocalAddressKind.VPN || it.kind == LocalAddressKind.CELLULAR } == true) {
            item { OtherNetworkAddresses(snapshot.addresses, addressPort, onCopy) }
        }
        if (history.events.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(strings.getString(R.string.ui_recent_requests), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onRequests) { Text(strings.getString(R.string.ui_all)) }
                }
            }
            items(history.events.take(2), key = { it.id }) { event ->
                RecentRequestPreview(event, onClick = { onRequest(event) })
            }
        }

    }
}

@Composable private fun ConnectionField(label: String, value: String, secret: Boolean = false, onCopy: (String) -> Unit) {
    val strings = LocalResources.current
    var revealed by rememberSaveable { mutableStateOf(false) }
    OutlinedCard {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 8.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val prefix = when { value.startsWith("sk-") -> "sk-"; value.startsWith("pp_") -> "pp_"; else -> "" }
                Text(if (secret && !revealed) "${prefix}••••••••••••••••" else value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                if (secret) IconButton(onClick = { revealed = !revealed }) { Icon(if (revealed) PathPilotIcons.VisibilityOff else PathPilotIcons.Visibility, if (revealed) strings.getString(R.string.ui_hide_key) else strings.getString(R.string.ui_show_key)) }
                IconButton(onClick = { onCopy(value) }) { Icon(PathPilotIcons.Copy, strings.getString(R.string.ui_copy_value, label)) }
            }
        }
    }
}

@Composable internal fun ProvidersScreen(config: RouterConfig, sessions: Set<String>, saving: Boolean,
    onAdd: () -> Unit, onEdit: (ProviderConfig) -> Unit, onToggle: (ProviderConfig, Boolean) -> Unit,
    onLogin: (ProviderConfig) -> Unit, onRoute: (ProviderConfig) -> Unit,
    onModels: (ProviderConfig) -> Unit,
    onManageModels: (ProviderConfig) -> Unit,
) {
    val strings = LocalResources.current
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (config.providers.isEmpty()) item { EmptyPage(strings.getString(R.string.ui_no_providers), strings.getString(R.string.ui_add_provider), saving, onAdd) }
        items(config.providers, key = { it.id }) { provider ->
            OutlinedCard(onClick = { onEdit(provider) }, enabled = !saving) {
                Column(Modifier.padding(start = 16.dp, top = 10.dp, end = 12.dp, bottom = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ProviderBrandIcon(provider.kind, Modifier.size(28.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(provider.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (provider.kind == ProviderKind.CODEX) { if (provider.id in sessions) strings.getString(R.string.ui_signed_in) else strings.getString(R.string.ui_signed_out) } else provider.baseUrl,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Switch(checked = provider.enabled, enabled = !saving, onCheckedChange = { onToggle(provider, it) },
                            modifier = Modifier.semantics { contentDescription = strings.getString(R.string.ui_enable_named, provider.name) })
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(enabled = !saving, modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 0.dp), onClick = { onManageModels(provider) }) {
                            Text(strings.getString(R.string.ui_model_count, config.routes.count { it.providerId == provider.id }))
                        }
                        if (provider.kind == ProviderKind.CODEX) TextButton(enabled = !saving, onClick = { onLogin(provider) }) { Text(if (provider.id in sessions) strings.getString(R.string.ui_sign_in_again) else strings.getString(R.string.ui_sign_in)) }
                        if (provider.kind == ProviderKind.CODEX && provider.id in sessions) TextButton(enabled = !saving, onClick = { onModels(provider) }) { Text(strings.getString(R.string.ui_fetch_models)) }
                        if (provider.kind != ProviderKind.CODEX) TextButton(enabled = !saving, onClick = { onRoute(provider) }) { Text(strings.getString(R.string.ui_add_model)) }
                    }
                }
            }
        }
    }
}

@Composable private fun EmptyPage(title: String, action: String, saving: Boolean, onAction: () -> Unit) {
    val strings = LocalResources.current
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        FilledTonalButton(enabled = !saving, onClick = onAction, shapes = ButtonDefaults.shapes()) {
            Icon(PathPilotIcons.Add, null); Spacer(Modifier.width(8.dp)); Text(action)
        }
    }
}
