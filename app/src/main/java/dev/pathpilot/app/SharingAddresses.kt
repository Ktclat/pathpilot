package dev.pathpilot.app

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
    val local = snapshot?.addresses.orEmpty().filter {
        it.kind != LocalAddressKind.VPN && it.kind != LocalAddressKind.CELLULAR
    }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var showGuide by rememberSaveable { mutableStateOf(false) }
    val selected = local.find { it.key() == selectedKey } ?: local.firstOrNull()
    val baseUrl = if (onThisDevice) "http://127.0.0.1:$port/v1" else selected?.let { "http://${it.ip}:$port/v1" }
    LaunchedEffect(local) {
        if (local.none { it.key() == selectedKey }) selectedKey = local.firstOrNull()?.key()
        if (local.size < 2) choosing = false
    }
    OutlinedCard {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("API 地址", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                SegmentedButton(selected = onThisDevice, onClick = { onDeviceChange(true); choosing = false },
                    shape = SegmentedButtonDefaults.itemShape(0, 2), modifier = Modifier.fillMaxHeight()) { Text("本机") }
                SegmentedButton(selected = !onThisDevice, onClick = { onDeviceChange(false) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2), modifier = Modifier.fillMaxHeight()) { Text("局域网") }
            }
            if (!onThisDevice) {
                snapshot?.warning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                if (selected == null) Text(
                    if (snapshot == null) "正在读取网络地址…" else "连接 Wi-Fi、网线或开启热点后显示地址",
                    Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ) else {
                    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(selected.label(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                }
            }
            baseUrl?.let { UrlRow(it, "API 地址", onCopy) }
            TextButton(onClick = { showGuide = true }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
                Text("OpenAI 兼容 · 接入说明")
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
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )) {
        LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("接入说明", style = MaterialTheme.typography.headlineSmall) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("客户端类型：OpenAI 兼容", style = MaterialTheme.typography.titleMedium)
                    Text(if (onThisDevice) "本机地址供当前手机上的其他应用使用。" else "局域网地址供同一网络中的其他设备使用。",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("HTTP · JSON / SSE · Bearer 鉴权", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                OutlinedCard {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("API 地址（Base URL）", style = MaterialTheme.typography.labelLarge)
                        if (baseUrl == null) Text("连接局域网后显示地址", Modifier.padding(vertical = 12.dp))
                        else UrlRow(baseUrl, "API 地址", onCopy)
                        Text("已包含 /v1；仅在客户端要求完整接口地址时使用下方端点。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { Text("API Key 填写首页的访问密钥；模型填写「模型」页中的共享模型名。",
                style = MaterialTheme.typography.bodyMedium) }
            item { Text("接口", style = MaterialTheme.typography.titleMedium) }
            item { EndpointGuide("Chat Completions", "POST", "/chat/completions", baseUrl,
                "DeepSeek、Codex；自定义供应商需上游支持。", onCopy) }
            item { EndpointGuide("Responses", "POST", "/responses", baseUrl,
                "Codex；自定义供应商需上游支持。DeepSeek 不提供此接口。", onCopy) }
            item { EndpointGuide("模型列表", "GET", "/models", baseUrl,
                "返回已启用供应商下的共享模型名。", onCopy) }
            item { Text("先开启首页的模型共享。本机与局域网共用开关、密钥和模型配置；切换地址只改变展示与复制内容。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable private fun EndpointGuide(title: String, method: String, path: String, baseUrl: String?, description: String,
    onCopy: (String, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(method, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (baseUrl != null) UrlRow("$baseUrl$path", "$title 接口地址", onCopy)
        else Text("/v1$path", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable private fun AddressRow(address: LocalAddress, port: Int, onCopy: (String, String) -> Unit) {
    val url = "http://${address.ip}:$port/v1"
    Column {
        Text(address.label(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        UrlRow(url, "API 地址", onCopy)
    }
}

@Composable private fun UrlRow(url: String, label: String, onCopy: (String, String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(url, Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = { onCopy(label, url) }) { Icon(PathPilotIcons.Copy, "复制$label") }
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
