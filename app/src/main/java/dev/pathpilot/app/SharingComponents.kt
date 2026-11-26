package dev.pathpilot.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.pathpilot.app.runtime.LocalAddress
import dev.pathpilot.app.runtime.LocalAddressKind
import dev.pathpilot.app.runtime.LocalAddressSnapshot
import dev.pathpilot.app.runtime.LocalAddresses
import dev.pathpilot.app.ui.i18n.localize
import dev.pathpilot.app.ui.icons.PathPilotIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
internal fun SharingControlCard(
    title: String,
    status: String,
    checked: Boolean,
    enabled: Boolean,
    running: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    switchLabel: String = title,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = if (running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(PathPilotIcons.Share, null, Modifier.size(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(status, style = MaterialTheme.typography.bodyMedium)
            }
            Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange,
                modifier = Modifier.semantics { contentDescription = switchLabel })
        }
    }
}

@Composable
internal fun AccessKeyRow(accessKey: String, onCopy: (String) -> Unit) {
    val strings = LocalResources.current
    var revealed by remember(accessKey) { mutableStateOf(false) }
    val prefix = when {
        accessKey.startsWith("sk-") -> "sk-"
        accessKey.startsWith("pp_") -> "pp_"
        else -> ""
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (revealed) accessKey else "${prefix}••••••••••••••••", Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        IconButton(onClick = { revealed = !revealed }) {
            Icon(if (revealed) PathPilotIcons.VisibilityOff else PathPilotIcons.Visibility,
                stringResource(if (revealed) R.string.ui_hide_key else R.string.ui_show_key))
        }
        IconButton(onClick = { onCopy(accessKey) }) {
            Icon(PathPilotIcons.Copy, strings.getString(R.string.ui_copy_value, strings.getString(R.string.ui_access_key)))
        }
    }
}

/** A running service supplies its observed interfaces; otherwise discovery follows the visible UI. */
@Composable
internal fun rememberSharingAddresses(
    running: Boolean,
    stopping: Boolean,
    addresses: List<LocalAddress>,
    warning: String?,
): LocalAddressSnapshot? {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val discovered by produceState<LocalAddressSnapshot?>(null, context, lifecycle, running, stopping) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!running && !stopping) while (isActive) {
                value = withContext(Dispatchers.IO) { LocalAddresses.discover(context) }
                delay(5_000)
            }
        }
    }
    return if (running || stopping) LocalAddressSnapshot(addresses, warning) else discovered
}

@Composable
internal fun SharingAddressSelector(
    snapshot: LocalAddressSnapshot?,
    port: Int,
    path: String,
    onThisDevice: Boolean,
    onDeviceChange: (Boolean) -> Unit,
    addressLabel: String,
    onCopy: (String, String) -> Unit,
    footer: @Composable (String?) -> Unit = {},
) {
    val strings = LocalResources.current
    val local = snapshot?.addresses.orEmpty().filter {
        it.kind != LocalAddressKind.VPN && it.kind != LocalAddressKind.CELLULAR
    }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }
    val selected = local.find { it.key() == selectedKey } ?: local.firstOrNull()
    val url = if (onThisDevice) "http://127.0.0.1:$port$path" else selected?.let { "http://${it.ip}:$port$path" }
    LaunchedEffect(local) {
        if (local.none { it.key() == selectedKey }) selectedKey = local.firstOrNull()?.key()
        if (local.size < 2) choosing = false
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            SegmentedButton(selected = onThisDevice, onClick = { onDeviceChange(true); choosing = false },
                shape = SegmentedButtonDefaults.itemShape(0, 2), modifier = Modifier.fillMaxHeight()) {
                Text(stringResource(R.string.ui_this_device))
            }
            SegmentedButton(selected = !onThisDevice, onClick = { onDeviceChange(false) },
                shape = SegmentedButtonDefaults.itemShape(1, 2), modifier = Modifier.fillMaxHeight()) {
                Text(stringResource(R.string.ui_lan))
            }
        }
        if (!onThisDevice) {
            snapshot?.warning?.let {
                Text(strings.localize(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            if (selected == null) Text(
                stringResource(if (snapshot == null) R.string.ui_reading_addresses else R.string.ui_connect_network),
                Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ) else Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(selected.label(strings), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (local.size > 1) Box {
                    TextButton(onClick = { choosing = true }) {
                        Text(stringResource(R.string.ui_address_count, local.size))
                        Icon(PathPilotIcons.ExpandMore, null, Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                        Text(stringResource(R.string.ui_choose_client_network), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        local.forEach { address ->
                            DropdownMenuItem(text = {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(address.label(strings))
                                    Text(address.ip, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                                }
                            }, onClick = { selectedKey = address.key(); choosing = false },
                                trailingIcon = if (address == selected) { {
                                    Icon(PathPilotIcons.Check, stringResource(R.string.ui_current_address), Modifier.size(20.dp))
                                } } else null)
                        }
                    }
                }
            }
        }
        url?.let { UrlRow(it, addressLabel, onCopy) }
        footer(url)
    }
}

@Composable
internal fun UrlRow(url: String, label: String, onCopy: (String, String) -> Unit) {
    val strings = LocalResources.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(url, Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = { onCopy(label, url) }) {
            Icon(PathPilotIcons.Copy, strings.getString(R.string.ui_copy_value, label))
        }
    }
}

private fun LocalAddress.key(): String = "${interfaceName}|${ip}"

internal fun LocalAddress.label(strings: android.content.res.Resources): String {
    val kindLabel = when (kind) {
        LocalAddressKind.WIFI -> "Wi-Fi"
        LocalAddressKind.ETHERNET -> strings.getString(R.string.ui_ethernet)
        LocalAddressKind.OTHER -> strings.getString(R.string.ui_network_interface)
        LocalAddressKind.VPN -> "VPN"
        LocalAddressKind.CELLULAR -> strings.getString(R.string.ui_cellular)
    }
    return if (interfaceName.isBlank()) kindLabel else "$kindLabel · $interfaceName"
}
