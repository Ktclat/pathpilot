package dev.pathpilot.app

import androidx.compose.foundation.layout.*
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
    snapshot: LocalAddressSnapshot?, port: Int, onCopy: (String, String) -> Unit,
) {
    if (snapshot == null) {
        Text("正在读取网络地址…", style = MaterialTheme.typography.bodyMedium)
        return
    }
    val (local, other) = snapshot.addresses.partition {
        it.kind != LocalAddressKind.VPN && it.kind != LocalAddressKind.CELLULAR
    }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }
    val selected = local.find { it.key() == selectedKey } ?: local.firstOrNull()
    LaunchedEffect(local) {
        if (local.none { it.key() == selectedKey }) selectedKey = local.firstOrNull()?.key()
        if (local.size < 2) choosing = false
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        snapshot.warning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        if (selected == null) {
            Text(if (other.isEmpty() && snapshot.warning == null) "连接 Wi-Fi、网线或开启热点后显示地址" else "未发现局域网地址",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else OutlinedCard {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("API 地址", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(selected.label(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (local.size > 1) Box {
                        TextButton(onClick = { choosing = true }) {
                            Text("${local.size} 个地址")
                            Icon(PathPilotIcons.ExpandMore, null, Modifier.size(18.dp))
                        }
                        DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                            Text("选择客户端所在网络", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            local.forEach { address ->
                                DropdownMenuItem(text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(address.label())
                                        Text(address.ip, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }, onClick = { selectedKey = address.key(); choosing = false },
                                    trailingIcon = if (address == selected) { { Icon(PathPilotIcons.Check, "当前地址", Modifier.size(20.dp)) } } else null)
                            }
                        }
                    }
                }
                AddressRow(selected, port, onCopy, showLabel = false)
            }
        }
    }
}

@Composable internal fun OtherNetworkAddresses(addresses: List<LocalAddress>, port: Int, onCopy: (String, String) -> Unit) {
    val other = addresses.filter { it.kind == LocalAddressKind.VPN || it.kind == LocalAddressKind.CELLULAR }
    var showOther by rememberSaveable { mutableStateOf(false) }
    if (other.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { showOther = !showOther }) {
                Text("其他网络地址（${other.size}）")
                Icon(PathPilotIcons.ExpandMore, null, Modifier.size(18.dp).rotate(if (showOther) 180f else 0f))
            }
            if (showOther) OutlinedCard {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("VPN、蜂窝地址需单独确认客户端可达", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    other.forEachIndexed { index, address ->
                        if (index > 0) HorizontalDivider()
                        AddressRow(address, port, onCopy)
                    }
                }
            }
        }
    }
}

@Composable private fun AddressRow(address: LocalAddress, port: Int, onCopy: (String, String) -> Unit, showLabel: Boolean = true) {
    val url = "http://${address.ip}:$port/v1"
    Column {
        if (showLabel) Text(address.label(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(url, Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = { onCopy("API 地址", url) }) { Icon(PathPilotIcons.Copy, "复制 ${address.ip} 的 API 地址") }
        }
    }
}

private fun LocalAddress.key(): String = "$interfaceName|$ip"

private fun LocalAddress.label(): String {
    val kindLabel = when (kind) {
        LocalAddressKind.WIFI -> "Wi-Fi"
        LocalAddressKind.ETHERNET -> "有线网络"
        LocalAddressKind.OTHER -> "网络接口"
        LocalAddressKind.VPN -> "VPN"
        LocalAddressKind.CELLULAR -> "蜂窝网络"
    }
    return if (interfaceName.isBlank()) kindLabel else "$kindLabel · $interfaceName"
}
