package dev.pathpilot.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pathpilot.app.runtime.*
import dev.pathpilot.app.ui.icons.PathPilotIcons
import dev.pathpilot.app.ui.i18n.localize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
internal fun McpAccessCard(connectionId: String, port: Int, accessKey: String, runtime: McpRuntimeState, onCopy: (String, Boolean) -> Unit) {
    val context = LocalContext.current
    val strings = LocalResources.current
    var onThisDevice by rememberSaveable { mutableStateOf(true) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    val discovered by produceState<LocalAddressSnapshot?>(null, runtime.running) {
        while (isActive && !runtime.running) {
            value = withContext(Dispatchers.IO) { LocalAddresses.discover(context) }
            delay(5_000)
        }
    }
    val snapshot = if (runtime.running) LocalAddressSnapshot(runtime.addresses, runtime.addressWarning) else discovered
    val addresses = snapshot?.addresses.orEmpty().filter { it.kind != LocalAddressKind.VPN && it.kind != LocalAddressKind.CELLULAR }
    val selected = addresses.find { "${it.interfaceName}|${it.ip}" == selectedKey } ?: addresses.firstOrNull()
    val address = if (onThisDevice) "http://127.0.0.1:$port/mcp/$connectionId"
        else selected?.let { "http://${it.ip}:$port/mcp/$connectionId" }
    LaunchedEffect(addresses) {
        if (addresses.none { "${it.interfaceName}|${it.ip}" == selectedKey }) selectedKey = selected?.let { "${it.interfaceName}|${it.ip}" }
        if (addresses.size < 2) choosing = false
    }
    OutlinedCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.mcp_endpoint), style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                SegmentedButton(selected = onThisDevice, onClick = { onThisDevice = true },
                    shape = SegmentedButtonDefaults.itemShape(0, 2), modifier = Modifier.fillMaxHeight()) { Text(stringResource(R.string.mcp_local)) }
                SegmentedButton(selected = !onThisDevice, onClick = { onThisDevice = false },
                    shape = SegmentedButtonDefaults.itemShape(1, 2), modifier = Modifier.fillMaxHeight()) { Text(stringResource(R.string.mcp_lan)) }
            }
            if (!onThisDevice) {
                snapshot?.warning?.let { Text(strings.localize(it), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error) }
                if (selected == null) Text(stringResource(if (snapshot == null) R.string.mcp_loading_addresses else R.string.mcp_no_lan),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(mcpNetworkLabel(selected), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (addresses.size > 1) Box {
                        TextButton(onClick = { choosing = true }) { Text(stringResource(R.string.mcp_addresses, addresses.size)) }
                        DropdownMenu(choosing, onDismissRequest = { choosing = false }) {
                            addresses.forEach { candidate ->
                                DropdownMenuItem(text = { Column {
                                    Text(mcpNetworkLabel(candidate))
                                    Text(candidate.ip, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                } }, onClick = { selectedKey = "${candidate.interfaceName}|${candidate.ip}"; choosing = false })
                            }
                        }
                    }
                }
            }
            address?.let { url ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(url, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                    IconButton(onClick = { onCopy(url, false) }) { Icon(PathPilotIcons.Copy, stringResource(R.string.mcp_copy_address)) }
                }
            }
            HorizontalDivider()
            Text(stringResource(R.string.mcp_access_key), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (showKey) accessKey else "sk-••••••••••••", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) PathPilotIcons.VisibilityOff else PathPilotIcons.Visibility,
                    stringResource(if (showKey) R.string.mcp_hide_key else R.string.mcp_show_key)) }
                IconButton(onClick = { onCopy(accessKey, true) }) { Icon(PathPilotIcons.Copy, stringResource(R.string.mcp_copy_key)) }
            }
        }
    }
}

@Composable
private fun mcpNetworkLabel(address: LocalAddress): String {
    val kind = stringResource(when (address.kind) {
        LocalAddressKind.WIFI -> R.string.mcp_network_wifi
        LocalAddressKind.ETHERNET -> R.string.mcp_network_ethernet
        else -> R.string.mcp_network_other
    })
    return if (address.interfaceName.isBlank()) kind else "$kind · ${address.interfaceName}"
}

internal fun copyMcpValue(context: Context, value: String, secret: Boolean) {
    val clip = ClipData.newPlainText(context.getString(if (secret) R.string.mcp_access_key else R.string.mcp_endpoint), value)
    if (secret && Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply {
        putBoolean("android.content.extra.IS_SENSITIVE", true)
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}
