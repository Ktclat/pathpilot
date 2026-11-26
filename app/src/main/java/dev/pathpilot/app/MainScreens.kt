package dev.pathpilot.app

import dev.pathpilot.app.ui.i18n.localize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import dev.pathpilot.app.runtime.LocalAddressKind
import dev.pathpilot.app.runtime.RuntimeState
import dev.pathpilot.app.runtime.RequestHistoryState
import dev.pathpilot.app.ui.icons.PathPilotIcons
import dev.pathpilot.core.*

@Composable internal fun SharingScreen(
    config: RouterConfig, runtime: RuntimeState, sessions: Set<String>, saving: Boolean, notificationPending: Boolean,
    onSharingChange: (Boolean) -> Unit, onProviders: () -> Unit, onRoutes: () -> Unit, onCopy: (String, String) -> Unit,
    history: RequestHistoryState, onRequests: () -> Unit, onRequest: (RequestEvent) -> Unit,
) {
    val strings = LocalResources.current
    val snapshot = rememberSharingAddresses(runtime.running, runtime.stopping, runtime.addresses, runtime.addressWarning)
    val addressPort = if (runtime.running || runtime.stopping) runtime.port else config.port
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
            SharingControlCard(
                title = strings.getString(R.string.ui_model_sharing), status = status,
                checked = checked, enabled = canToggle, running = runtime.running,
                onCheckedChange = onSharingChange,
                switchLabel = strings.getString(R.string.ui_model_sharing_switch),
            )
        }
        runtime.lastError?.let { message -> item { Text(strings.localize(message), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) } }
        item { SharingAddresses(snapshot, addressPort,
            onThisDevice, { onThisDevice = it }, onCopy) }
        item {
            OutlinedCard {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 8.dp)) {
                    Text(strings.getString(R.string.ui_access_key), style = MaterialTheme.typography.titleMedium)
                    AccessKeyRow(config.accessKey, onCopy = { onCopy(strings.getString(R.string.ui_access_key), it) })
                }
            }
        }
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
