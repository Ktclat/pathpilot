package dev.pathpilot.app

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
import dev.pathpilot.app.runtime.LocalAddresses
import dev.pathpilot.app.runtime.RuntimeState
import dev.pathpilot.app.ui.icons.PathPilotIcons
import dev.pathpilot.app.ui.icons.ProviderBrandIcon
import dev.pathpilot.core.*
import dev.pathpilot.core.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

@Composable internal fun SharingScreen(
    config: RouterConfig, runtime: RuntimeState, sessions: Set<String>, saving: Boolean,
    onToggle: () -> Unit, onProviders: () -> Unit, onRoutes: () -> Unit, onCopy: (String, String) -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val localAddresses by produceState(emptyList<String>(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            value = withContext(Dispatchers.IO) { LocalAddresses.ipv4() }
            awaitCancellation()
        }
    }
    val addresses = if (runtime.running) runtime.addresses else localAddresses
    val enabledProviders = config.providers.filter { it.enabled }
    val enabledRoutes = config.routes.filter { it.enabled }
    val ready = enabledRoutes.any { route -> enabledProviders.any { p ->
        p.id == route.providerId && (p.kind != ProviderKind.CODEX || p.id in sessions)
    } }
    val action = when {
        runtime.running -> "停止共享"
        runtime.starting -> "正在启动…"
        config.providers.isEmpty() -> "添加供应商"
        enabledProviders.isEmpty() -> "启用供应商"
        enabledRoutes.isEmpty() -> "配置路由"
        !ready -> "检查供应商"
        else -> "开启共享"
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = if (runtime.running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer), shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(PathPilotIcons.Share, null, Modifier.size(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text(when { runtime.running -> "共享中"; runtime.starting -> "正在启动"; !ready -> "尚未配置"; else -> "未启动" }, style = MaterialTheme.typography.titleLarge)
                            if (ready || runtime.running) Text("端口 ${if (runtime.running) runtime.port else config.port}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Button(enabled = (runtime.running || !saving) && !runtime.starting, shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight),
                        modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight),
                        onClick = {
                            when {
                                runtime.running || ready -> onToggle()
                                config.providers.isEmpty() || enabledProviders.isEmpty() -> onProviders()
                                enabledRoutes.isEmpty() -> onRoutes()
                                else -> onProviders()
                            }
                        }) {
                        Icon(if (runtime.running) PathPilotIcons.Stop else if (ready) PathPilotIcons.Play else PathPilotIcons.Add, null)
                        Spacer(Modifier.width(8.dp)); Text(action)
                    }
                }
            }
        }
        runtime.lastError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) } }
        item { Text("${config.providers.size} 个供应商  ·  ${config.routes.size} 条路由  ·  ${runtime.requestCount} 次请求", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (addresses.isEmpty()) item { Text("连接 Wi-Fi 或开启热点后显示地址", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(addresses, key = { it }) { address ->
            ConnectionField("API 地址", "http://$address:${if (runtime.running) runtime.port else config.port}/v1", onCopy = { onCopy("API 地址", it) })
        }
        item { ConnectionField("访问密钥", config.accessKey, secret = true, onCopy = { onCopy("访问密钥", it) }) }
        if (runtime.recentRequests.isNotEmpty()) {
            item { Text("最近请求", style = MaterialTheme.typography.titleMedium) }
            items(runtime.recentRequests.take(12)) { event ->
                ListItem(content = { Text(event.model.ifBlank { "未知模型" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${event.durationMs} ms") },
                    trailingContent = { Text(event.status.toString(), color = if (event.status >= 400) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) })
            }
        }
    }
}

@Composable private fun ConnectionField(label: String, value: String, secret: Boolean = false, onCopy: (String) -> Unit) {
    var revealed by rememberSaveable { mutableStateOf(false) }
    OutlinedCard {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val prefix = when { value.startsWith("sk-") -> "sk-"; value.startsWith("pp_") -> "pp_"; else -> "" }
                Text(if (secret && !revealed) "${prefix}••••••••••••••••" else value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                if (secret) IconButton(onClick = { revealed = !revealed }) { Icon(if (revealed) PathPilotIcons.VisibilityOff else PathPilotIcons.Visibility, if (revealed) "隐藏密钥" else "显示密钥") }
                IconButton(onClick = { onCopy(value) }) { Icon(PathPilotIcons.Copy, "复制$label") }
            }
        }
    }
}

@Composable internal fun ProvidersScreen(config: RouterConfig, sessions: Set<String>, saving: Boolean,
    onAdd: () -> Unit, onEdit: (ProviderConfig) -> Unit, onToggle: (ProviderConfig, Boolean) -> Unit,
    onLogin: (ProviderConfig) -> Unit, onRoute: (ProviderConfig) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (config.providers.isEmpty()) item { EmptyPage("还没有供应商", "添加供应商", saving, onAdd) }
        items(config.providers, key = { it.id }) { provider ->
            OutlinedCard(onClick = { onEdit(provider) }, enabled = !saving) {
                Column(Modifier.padding(start = 16.dp, top = 10.dp, end = 12.dp, bottom = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ProviderBrandIcon(provider.kind, Modifier.size(28.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(provider.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (provider.kind == ProviderKind.CODEX) { if (provider.id in sessions) "已登录" else "未登录" } else provider.baseUrl,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Switch(checked = provider.enabled, enabled = !saving, onCheckedChange = { onToggle(provider, it) },
                            modifier = Modifier.semantics { contentDescription = "启用 ${provider.name}" })
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${config.routes.count { it.providerId == provider.id }} 条路由", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (provider.kind == ProviderKind.CODEX) TextButton(enabled = !saving, onClick = { onLogin(provider) }) { Text(if (provider.id in sessions) "重新登录" else "登录") }
                        TextButton(enabled = !saving, onClick = { onRoute(provider) }) { Text("添加路由") }
                    }
                }
            }
        }
    }
}

@Composable internal fun RoutesScreen(config: RouterConfig, saving: Boolean, onAdd: () -> Unit,
    onEdit: (RouteConfig) -> Unit, onToggle: (RouteConfig, Boolean) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (config.routes.isEmpty()) item { EmptyPage("还没有模型路由", if (config.providers.isEmpty()) "添加供应商" else "添加路由", saving, onAdd) }
        items(config.routes, key = { it.alias }) { route ->
            OutlinedCard(onClick = { onEdit(route) }, enabled = !saving) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(route.alias, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                        Text("${config.providers.find { it.id == route.providerId }?.name.orEmpty()} · ${route.upstreamModel}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = route.enabled, enabled = !saving, onCheckedChange = { onToggle(route, it) }, modifier = Modifier.semantics { contentDescription = "启用 ${route.alias}" })
                }
            }
        }
    }
}

@Composable private fun EmptyPage(title: String, action: String, saving: Boolean, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        FilledTonalButton(enabled = !saving, onClick = onAction, shapes = ButtonDefaults.shapes()) {
            Icon(PathPilotIcons.Add, null); Spacer(Modifier.width(8.dp)); Text(action)
        }
    }
}

@Composable internal fun SettingsScreen(config: RouterConfig, running: Boolean, saving: Boolean,
    onPort: (Int, () -> Unit) -> Unit, onProxy: (OutboundProxyConfig, () -> Unit) -> Unit, onRotate: (() -> Unit) -> Unit,
) {
    val context = LocalContext.current
    fun open(intent: Intent) {
        try { context.startActivity(intent) }
        catch (_: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(context, "没有可打开此项的应用", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    var editPort by rememberSaveable { mutableStateOf(false) }
    var editProxy by rememberSaveable { mutableStateOf(false) }
    var rotate by rememberSaveable { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        SettingsLabel("连接")
        SettingsRow("监听端口", if (running) "${config.port} · 停止共享后修改" else config.port.toString(), enabled = !saving && !running) { editPort = true }
        SettingsRow("上游代理", proxyLabel(config.outboundProxy), enabled = !saving) { editProxy = true }
        SettingsRow("重置访问密钥", enabled = !saving) { rotate = true }
        SettingsLabel("应用")
        SettingsRow("后台运行", "电池设置") { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        SettingsRow("使用说明") { open(Intent(Intent.ACTION_VIEW, "https://github.com/Ktclat/pathpilot#readme".toUri())) }
        SettingsRow("关于 PathPilot", BuildConfig.VERSION_NAME) { about = true }
    }
    if (editPort) PortDialog(config.port, saving, { editPort = false }) { port -> onPort(port) { editPort = false } }
    if (editProxy) ProxyDialog(config.outboundProxy, saving, { editProxy = false }) { proxy -> onProxy(proxy) { editProxy = false } }
    if (rotate) AlertDialog(onDismissRequest = { if (!saving) rotate = false }, title = { Text("重置访问密钥？") },
        text = { Text("旧密钥将失效，需要更新其他设备。") },
        confirmButton = { TextButton(enabled = !saving, onClick = { onRotate { rotate = false } }) { Text("重置") } },
        dismissButton = { TextButton(enabled = !saving, onClick = { rotate = false }) { Text("取消") } })
    if (about) AlertDialog(onDismissRequest = { about = false }, title = { Text("PathPilot ${BuildConfig.VERSION_NAME}") },
        text = { Text("Android AI 路由与局域网共享\nMaterial 3 Expressive · Monet\nARM64 · Android 8.0+") },
        confirmButton = { TextButton(onClick = { about = false }) { Text("关闭") } })
}

@Composable private fun SettingsLabel(text: String) { Text(text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
@Composable private fun SettingsRow(title: String, summary: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(modifier = Modifier.clickable(enabled = enabled, onClick = onClick), content = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Icon(PathPilotIcons.ExpandMore, null) }, enabled = enabled)
}

@Composable private fun PortDialog(current: Int, saving: Boolean, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var input by rememberSaveable { mutableStateOf(current.toString()) }
    val port = input.toIntOrNull()
    val valid = port != null && port in 1024..65535
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("监听端口") }, text = {
        OutlinedTextField(input, { input = it }, enabled = !saving, label = { Text("端口") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = !valid,
            supportingText = if (!valid) { { Text("填写 1024–65535") } } else null)
    }, confirmButton = { TextButton(enabled = !saving && valid, onClick = { onSave(requireNotNull(port)) }) { Text("保存") } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") } })
}

private fun proxyLabel(config: OutboundProxyConfig): String = when (config.mode) {
    OutboundProxyMode.SYSTEM -> "跟随系统"
    OutboundProxyMode.HTTP -> "HTTP · ${config.host}:${config.port}"
    OutboundProxyMode.SOCKS5 -> "SOCKS5 · ${config.host}:${config.port}"
}

@Composable private fun ProxyDialog(current: OutboundProxyConfig, saving: Boolean, onDismiss: () -> Unit, onSave: (OutboundProxyConfig) -> Unit) {
    var mode by rememberSaveable { mutableStateOf(current.mode) }
    var host by rememberSaveable { mutableStateOf(current.host.ifBlank { "127.0.0.1" }) }
    var port by rememberSaveable { mutableStateOf(current.port.toString()) }
    var submitted by remember { mutableStateOf(false) }
    val draft = OutboundProxyConfig(mode, host, port.toIntOrNull() ?: 0)
    val validation = OutboundProxyValidation.validate(draft)
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("上游代理") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutboundProxyMode.entries.forEach { option ->
                Row(Modifier.fillMaxWidth().selectable(selected = mode == option, enabled = !saving, role = Role.RadioButton, onClick = { mode = option }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == option, onClick = null, enabled = !saving)
                    Text(when (option) { OutboundProxyMode.SYSTEM -> "跟随系统"; OutboundProxyMode.HTTP -> "HTTP"; OutboundProxyMode.SOCKS5 -> "SOCKS5" })
                }
            }
            if (mode == OutboundProxyMode.SYSTEM) Text("使用手机 VPN 时选择此项", style = MaterialTheme.typography.bodySmall)
            else {
                OutlinedTextField(host, { host = it }, enabled = !saving, singleLine = true, label = { Text("主机") },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                    isError = submitted && validation.hostError != null,
                    supportingText = if (submitted && validation.hostError != null) { { Text(validation.hostError.orEmpty()) } } else null)
                OutlinedTextField(port, { port = it }, enabled = !saving, singleLine = true, label = { Text("端口") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = submitted && validation.portError != null,
                    supportingText = if (submitted && validation.portError != null) { { Text(validation.portError.orEmpty()) } } else null)
                Text("登录网页仍使用浏览器的网络设置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }, confirmButton = { TextButton(enabled = !saving, onClick = { submitted = true; if (validation.isValid) onSave(OutboundProxyValidation.normalized(draft)) }) { Text("保存") } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") } })
}
