package dev.pathpilot.app

import dev.pathpilot.app.ui.i18n.localize
import androidx.compose.ui.platform.LocalResources
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pathpilot.app.runtime.LocalAddress
import dev.pathpilot.app.runtime.LocalAddressKind
import dev.pathpilot.app.runtime.LocalAddressSnapshot
import dev.pathpilot.app.ui.icons.PathPilotIcons

@Composable internal fun SharingAddresses(
    snapshot: LocalAddressSnapshot?, port: Int, onThisDevice: Boolean, onDeviceChange: (Boolean) -> Unit,
    onCopy: (String, String) -> Unit,
) {
    val strings = LocalResources.current
    val local = snapshot?.addresses.orEmpty().filter {
        it.kind != LocalAddressKind.VPN && it.kind != LocalAddressKind.CELLULAR
    }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var showGuide by rememberSaveable { mutableStateOf(false) }
    val selected = local.find { it.key() == selectedKey } ?: local.firstOrNull()
    val baseUrl = if (onThisDevice) "http://127.0.0.1:${port}/v1" else selected?.let { "http://${it.ip}:${port}/v1" }
    LaunchedEffect(local) {
        if (local.none { it.key() == selectedKey }) selectedKey = local.firstOrNull()?.key()
        if (local.size < 2) choosing = false
    }
    OutlinedCard {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(strings.getString(R.string.ui_api_address), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                SegmentedButton(selected = onThisDevice, onClick = { onDeviceChange(true); choosing = false },
                    shape = SegmentedButtonDefaults.itemShape(0, 2), modifier = Modifier.fillMaxHeight()) { Text(strings.getString(R.string.ui_this_device)) }
                SegmentedButton(selected = !onThisDevice, onClick = { onDeviceChange(false) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2), modifier = Modifier.fillMaxHeight()) { Text(strings.getString(R.string.ui_lan)) }
            }
            if (!onThisDevice) {
                snapshot?.warning?.let { Text(strings.localize(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                if (selected == null) Text(
                    if (snapshot == null) strings.getString(R.string.ui_reading_addresses) else strings.getString(R.string.ui_connect_network),
                    Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ) else {
                    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(selected.label(strings), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (local.size > 1) Box {
                            TextButton(onClick = { choosing = true }) {
                                Text(strings.getString(R.string.ui_address_count, local.size))
                                Icon(PathPilotIcons.ExpandMore, null, Modifier.size(18.dp))
                            }
                            DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                                Text(strings.getString(R.string.ui_choose_client_network), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                local.forEach { address ->
                                    DropdownMenuItem(text = {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(address.label(strings))
                                            Text(address.ip, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }, onClick = { selectedKey = address.key(); choosing = false },
                                        trailingIcon = if (address == selected) { { Icon(PathPilotIcons.Check, strings.getString(R.string.ui_current_address), Modifier.size(20.dp)) } } else null)
                                }
                            }
                        }
                    }
                }
            }
            baseUrl?.let { UrlRow(it, strings.getString(R.string.ui_api_address), onCopy) }
            TextButton(onClick = { showGuide = true }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
                Text(strings.getString(R.string.ui_openai_connection_guide))
                Spacer(Modifier.width(4.dp))
                Icon(PathPilotIcons.ExpandMore, null, Modifier.size(18.dp))
            }
        }
    }
    if (showGuide) ConnectionGuide(baseUrl, onThisDevice, onCopy) { showGuide = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ConnectionGuide(baseUrl: String?, onThisDevice: Boolean,
    onCopy: (String, String) -> Unit, onDismiss: () -> Unit,
) {
    val strings = LocalResources.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )) {
        LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text(strings.getString(R.string.ui_connection_guide), style = MaterialTheme.typography.headlineSmall) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.getString(R.string.ui_client_openai_compatible), style = MaterialTheme.typography.titleMedium)
                    Text(if (onThisDevice) strings.getString(R.string.ui_loopback_address_help) else strings.getString(R.string.ui_lan_address_help),
                        style = MaterialTheme.typography.bodyMedium)
                    Text(strings.getString(R.string.ui_http_protocols), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                OutlinedCard {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(strings.getString(R.string.ui_api_base_url), style = MaterialTheme.typography.labelLarge)
                        if (baseUrl == null) Text(strings.getString(R.string.ui_connect_lan_for_address), Modifier.padding(vertical = 12.dp))
                        else UrlRow(baseUrl, strings.getString(R.string.ui_api_address), onCopy)
                        Text(strings.getString(R.string.ui_base_url_v1_help),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { Text(strings.getString(R.string.ui_client_key_model_help),
                style = MaterialTheme.typography.bodyMedium) }
            item { Text(strings.getString(R.string.ui_endpoint), style = MaterialTheme.typography.titleMedium) }
            item { EndpointGuide("Chat Completions", "POST", "/chat/completions", baseUrl,
                strings.getString(R.string.ui_chat_support), onCopy) }
            item { EndpointGuide("Responses", "POST", "/responses", baseUrl,
                strings.getString(R.string.ui_responses_support), onCopy) }
            item { EndpointGuide(strings.getString(R.string.ui_model_list), "GET", "/models", baseUrl,
                strings.getString(R.string.ui_models_endpoint_help), onCopy) }
            item { Text(strings.getString(R.string.ui_sharing_connection_help),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable private fun EndpointGuide(title: String, method: String, path: String, baseUrl: String?, description: String,
    onCopy: (String, String) -> Unit,
) {
    val strings = LocalResources.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(method, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (baseUrl != null) UrlRow("${baseUrl}${path}", strings.getString(R.string.ui_endpoint_address, title), onCopy)
        else Text("/v1${path}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun OtherNetworkAddresses(addresses: List<LocalAddress>, port: Int, onCopy: (String, String) -> Unit) {
    val strings = LocalResources.current
    val other = addresses.filter { it.kind == LocalAddressKind.VPN || it.kind == LocalAddressKind.CELLULAR }
    var showOther by rememberSaveable { mutableStateOf(false) }
    if (other.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { showOther = !showOther }) {
                Text(strings.getString(R.string.ui_other_addresses, other.size))
                Icon(PathPilotIcons.ExpandMore, null, Modifier.size(18.dp).rotate(if (showOther) 180f else 0f))
            }
            if (showOther) OutlinedCard {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.getString(R.string.ui_other_addresses_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    other.forEachIndexed { index, address ->
                        if (index > 0) HorizontalDivider()
                        AddressRow(address, port, onCopy)
                    }
                }
            }
        }
    }
}

@Composable private fun AddressRow(address: LocalAddress, port: Int, onCopy: (String, String) -> Unit) {
    val strings = LocalResources.current
    val url = "http://${address.ip}:${port}/v1"
    Column {
        Text(address.label(strings), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        UrlRow(url, strings.getString(R.string.ui_api_address), onCopy)
    }
}

@Composable private fun UrlRow(url: String, label: String, onCopy: (String, String) -> Unit) {
    val strings = LocalResources.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(url, Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = { onCopy(label, url) }) { Icon(PathPilotIcons.Copy, strings.getString(R.string.ui_copy_value, label)) }
    }
}

private fun LocalAddress.key(): String = "${interfaceName}|${ip}"

private fun LocalAddress.label(strings: android.content.res.Resources): String {
    val kindLabel = when (kind) {
        LocalAddressKind.WIFI -> "Wi-Fi"
        LocalAddressKind.ETHERNET -> strings.getString(R.string.ui_ethernet)
        LocalAddressKind.OTHER -> strings.getString(R.string.ui_network_interface)
        LocalAddressKind.VPN -> "VPN"
        LocalAddressKind.CELLULAR -> strings.getString(R.string.ui_cellular)
    }
    return if (interfaceName.isBlank()) kindLabel else "${kindLabel} · ${interfaceName}"
}
