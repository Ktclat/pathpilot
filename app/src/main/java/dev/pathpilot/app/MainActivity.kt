package dev.pathpilot.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pathpilot.app.runtime.*
import dev.pathpilot.core.*
import dev.pathpilot.core.codex.*
import kotlinx.coroutines.*
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG || !BuildConfig.ALLOW_SCREENSHOTS) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        enableEdgeToEdge()
        setContent { PathPilotTheme { PathPilotApp() } }
    }
}

@Composable private fun PathPilotTheme(content: @Composable () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(primary = Color(0xFFABD794), secondary = Color(0xFFB4CCC4), background = Color(0xFF111714), surface = Color(0xFF111714))
    else lightColorScheme(primary = Color(0xFF315D44), onPrimary = Color.White, primaryContainer = Color(0xFFD9EBCF), secondary = Color(0xFF52665B), background = Color(0xFFF7F8F2), surface = Color(0xFFF7F8F2), surfaceVariant = Color(0xFFE6EADF))
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PathPilotApp() {
    val context = LocalContext.current.applicationContext
    var store by remember { mutableStateOf<AppStore?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        loadFailed = false
        try { store = withContext(Dispatchers.IO) { AppStore.get(context) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { loadFailed = true }
    }
    if (store != null) PathPilotContent(requireNotNull(store))
    else Surface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("PathPilot", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            if (loadFailed) {
                Text("无法读取本机配置，请检查设备存储状态后重试。")
                TextButton(onClick = { attempt++ }) { Text("重试") }
            } else {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("正在读取加密配置…")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PathPilotContent(store: AppStore) {
    val context = LocalContext.current
    val config by store.config.collectAsStateWithLifecycle()
    val runtime by RouterRuntime.state.collectAsStateWithLifecycle()
    val storageError by store.storageError.collectAsStateWithLifecycle()
    val sessions by store.authenticatedCodexProviders.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var providerEditor by remember { mutableStateOf<ProviderConfig?>(null) }
    var addProvider by remember { mutableStateOf(false) }
    var routeEditor by remember { mutableStateOf<RouteConfig?>(null) }
    var addRoute by remember { mutableStateOf(false) }
    var codexLoginProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    val codexLogin = config.providers.firstOrNull { it.id == codexLoginProviderId }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun notify(message: String) { scope.launch { snackbar.showSnackbar(message) } }
    fun update(onSuccess: () -> Unit = {}, transform: (RouterConfig) -> RouterConfig) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { store.updateConfig(transform) }
                onSuccess()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                saveError = "配置未保存，原配置已保留。请检查存储状态后重试。"
            } finally {
                saving = false
            }
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        RouterService.start(context)
    }
    val tabs = listOf("共享", "供应商", "路由", "设置")
    val icons = listOf(Icons.Default.WifiTethering, Icons.Default.Dns, Icons.AutoMirrored.Filled.AltRoute, Icons.Default.Tune)
    Scaffold(
        topBar = { TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.AltRoute, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp)); Text("PathPilot", fontWeight = FontWeight.Bold)
        } }, actions = { Text("本机路由", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 20.dp)) }) },
        bottomBar = { NavigationBar { tabs.forEachIndexed { i, label ->
            NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icons[i], label) }, label = { Text(label) })
        } } },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (tab == 1 || tab == 2) ExtendedFloatingActionButton(
                onClick = { if (tab == 1) addProvider = true else if (config.providers.isEmpty()) notify("先添加一个供应商") else addRoute = true },
                icon = { Icon(Icons.Default.Add, null) }, text = { Text(if (tab == 1) "添加供应商" else "添加路由") }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            storageError?.let { Notice("凭据存储不可用", "为保护已有凭据，已阻止覆盖和启动。请查看设备存储状态；不要清除应用数据。") }
            when (tab) {
                0 -> {
                    Heading("让手机成为 AI 入口", "统一账号与模型，分享给身边的设备。")
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(28.dp)) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (runtime.running) Icons.Default.Sensors else Icons.Default.SensorsOff, null, Modifier.size(28.dp))
                                Spacer(Modifier.width(12.dp))
                                Column { Text(if (runtime.running) "局域网共享已开启" else "准备好，随时连接", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    Text(if (runtime.running) "保持手机与客户端连接到同一网络" else "开启后，由手机接收并转发 API 请求", style = MaterialTheme.typography.bodySmall) }
                            }
                            Button(onClick = {
                                if (runtime.running) RouterService.stop(context)
                                else if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else RouterService.start(context)
                            }, enabled = runtime.running || (!saving && storageError == null && config.routes.any { r -> r.enabled && config.providers.any { it.id == r.providerId && it.enabled && (it.kind != ProviderKind.CODEX || it.id in sessions) } }), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                                Icon(if (runtime.running) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                                Spacer(Modifier.width(8.dp)); Text(if (runtime.running) "停止共享" else "开启共享")
                            }
                        }
                    }
                    runtime.lastError?.let { Notice("共享状态", it) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Metric("供应商", config.providers.count { it.enabled }.toString(), Modifier.weight(1f))
                        Metric("路由", config.routes.count { it.enabled }.toString(), Modifier.weight(1f))
                        Metric("本次请求", runtime.requestCount.toString(), Modifier.weight(1f))
                    }
                    SectionTitle("连接信息")
                    val addresses = LocalAddresses.ipv4()
                    if (addresses.isEmpty()) Notice("尚未发现局域网地址", "连接 Wi-Fi 后重新查看此页。热点地址是否可用取决于设备网络设置。")
                    else addresses.forEach { address ->
                        CopyRow("API 地址${if (!runtime.running) " · 尚未启动" else ""}", "http://$address:${if (runtime.running) runtime.port else config.port}/v1", { copy(context, "API 地址", it); notify("地址已复制") })
                    }
                    CopyRow("独立访问密钥", config.accessKey, { copy(context, "PathPilot 访问密钥", it); notify("访问密钥已复制") }, secret = true)
                    Text("在客户端填写以上地址、密钥及路由别名。共享使用局域网 HTTP，仅在可信网络开启；上游请求使用 HTTPS。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (config.routes.isEmpty()) {
                        OutlinedCard { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("两步开始", style = MaterialTheme.typography.titleMedium)
                            Text("1. 添加 Codex 订阅或 API 供应商\n2. 为上游模型创建一个共享别名")
                            TextButton(onClick = { tab = 1 }) { Text("配置供应商"); Icon(Icons.AutoMirrored.Filled.ArrowForward, null) }
                        } }
                    }
                    if (runtime.recentRequests.isNotEmpty()) {
                        SectionTitle("最近请求")
                        runtime.recentRequests.take(12).forEach { event ->
                            ListItem(headlineContent = { Text(event.model.ifBlank { "未识别模型" }) }, supportingContent = { Text("${event.endpoint} · ${event.durationMs} ms") }, trailingContent = { Text(event.status.toString(), color = if (event.status < 400) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) })
                        }
                        Text("仅记录状态、模型和耗时，不记录提示词或回复。", style = MaterialTheme.typography.bodySmall)
                    }
                }
                1 -> {
                    Heading("你的模型来源", "订阅账号与 API 服务，集中在这里。")
                    if (config.providers.isEmpty()) EmptyState(Icons.Default.Dns, "还没有供应商", "添加 ChatGPT / Codex 订阅，或填写 DeepSeek API Key。")
                    config.providers.forEach { provider ->
                        OutlinedCard(onClick = { providerEditor = provider }) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (provider.kind == ProviderKind.CODEX) Icons.Default.AccountCircle else Icons.Default.Api, null, Modifier.size(30.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) { Text(provider.name, style = MaterialTheme.typography.titleMedium); Text(kindLabel(provider.kind), style = MaterialTheme.typography.bodySmall) }
                                    Switch(checked = provider.enabled, onCheckedChange = { enabled -> update { c -> c.copy(providers = c.providers.map { if (it.id == provider.id) it.copy(enabled = enabled) else it }) } })
                                }
                                if (provider.kind == ProviderKind.CODEX) {
                                    Text(if (provider.id in sessions) "已保存订阅授权" else "尚未登录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                    OutlinedButton(onClick = { codexLoginProviderId = provider.id }) { Text(if (provider.id in sessions) "重新登录" else "登录 ChatGPT 订阅") }
                                } else Text(provider.baseUrl, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            }
                        }
                    }
                }
                2 -> {
                    Heading("让请求找到模型", "客户端使用别名，手机负责选择上游。")
                    if (config.routes.isEmpty()) EmptyState(Icons.AutoMirrored.Filled.AltRoute, "创建第一条路由", "例如：把 deepseek-chat 指向你的 DeepSeek 供应商。")
                    config.routes.forEach { route ->
                        OutlinedCard(onClick = { routeEditor = route }) {
                            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(route.alias, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                                    Text("${config.providers.find { it.id == route.providerId }?.name ?: "供应商已移除"} → ${route.upstreamModel}", style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = route.enabled, onCheckedChange = { enabled -> update { c -> c.copy(routes = c.routes.map { if (it.alias == route.alias) it.copy(enabled = enabled) else it }) } })
                            }
                        }
                    }
                    Notice("接口按供应商能力转发", "Codex 支持 Responses 和聊天转换。DeepSeek 使用 Chat Completions；自定义供应商需支持你调用的接口。暂不提供自动故障切换。")
                }
                3 -> Settings(config, runtime.running, saving,
                    onPort = { port -> update(onSuccess = { notify("端口已保存") }) { it.copy(port = port) } },
                    onRotate = { done -> update(onSuccess = { done(); notify("访问密钥已更新，旧密钥立即失效") }) { it.copy(accessKey = AccessKeys.generate()) } },
                )
            }
        }
    }
    if (addProvider || providerEditor != null) ProviderDialog(providerEditor, saving, onDismiss = { if (!saving) { addProvider = false; providerEditor = null } }, onSave = { provider ->
        update(onSuccess = {
            addProvider = false; providerEditor = null
            if (provider.kind == ProviderKind.CODEX && provider.id !in store.authenticatedCodexProviders.value) codexLoginProviderId = provider.id
        }) { c -> c.copy(providers = c.providers.filterNot { it.id == provider.id } + provider) }
    }, onDelete = { provider ->
        // AppStore prunes the provider's tokens in the same encrypted atomic write.
        update(onSuccess = { providerEditor = null }) { c -> c.copy(providers = c.providers.filterNot { it.id == provider.id }, routes = c.routes.filterNot { it.providerId == provider.id }) }
    })
    if (addRoute || routeEditor != null) RouteDialog(routeEditor, config, saving, onDismiss = { if (!saving) { addRoute = false; routeEditor = null } }, onSave = { route ->
        val oldAlias = routeEditor?.alias
        update(onSuccess = { addRoute = false; routeEditor = null }) { c -> c.copy(routes = c.routes.filterNot { it.alias == oldAlias } + route) }
    }, onDelete = { route -> update(onSuccess = { routeEditor = null }) { c -> c.copy(routes = c.routes.filterNot { it.alias == route.alias }) } })
    codexLogin?.let { provider -> CodexLoginDialog(provider, store, onDismiss = { codexLoginProviderId = null }, onSuccess = { codexLoginProviderId = null; notify("订阅授权已保存，请添加模型路由") }) }
    saveError?.let { message ->
        AlertDialog(onDismissRequest = { saveError = null }, title = { Text("保存失败") },
            text = { Text(message) }, confirmButton = { TextButton(onClick = { saveError = null }) { Text("返回编辑") } })
    }
}

@Composable private fun Heading(title: String, subtitle: String) {
    Column(Modifier.padding(top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun SectionTitle(text: String) { Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
@Composable private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value, fontSize = 27.sp, fontWeight = FontWeight.Medium); Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
@Composable private fun Notice(title: String, body: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall); Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable private fun EmptyState(icon: ImageVector, title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge); Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}
@Composable private fun CopyRow(label: String, value: String, onCopy: (String) -> Unit, secret: Boolean = false) {
    var visible by remember { mutableStateOf(false) }
    OutlinedCard {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (secret && !visible) "pp_••••••••••••••••" else value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                if (secret) IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (visible) "隐藏密钥" else "显示密钥") }
                IconButton(onClick = { onCopy(value) }) { Icon(Icons.Default.ContentCopy, "复制$label") }
            }
        }
    }
}
private fun copy(context: Context, label: String, value: String) {
    val clip = ClipData.newPlainText(label, value)
    if (Build.VERSION.SDK_INT >= 33 && label.contains("密钥")) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
}
private fun kindLabel(kind: ProviderKind) = when (kind) { ProviderKind.CODEX -> "ChatGPT / Codex 订阅"; ProviderKind.DEEPSEEK -> "DeepSeek API"; ProviderKind.OPENAI_COMPATIBLE -> "OpenAI 兼容 API" }

@Composable private fun ProviderDialog(existing: ProviderConfig?, saving: Boolean, onDismiss: () -> Unit, onSave: (ProviderConfig) -> Unit, onDelete: (ProviderConfig) -> Unit) {
    var kind by remember { mutableStateOf(existing?.kind ?: ProviderKind.DEEPSEEK) }
    var name by remember { mutableStateOf(existing?.name ?: "DeepSeek") }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: "https://api.deepseek.com/v1") }
    var key by remember { mutableStateOf(existing?.apiKey ?: "") }
    var reveal by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val validUrl = runCatching { val uri = java.net.URI(baseUrl.trim()); uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null }.getOrDefault(false)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "添加供应商" else "编辑供应商") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (existing == null) ProviderKind.entries.forEach { option ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = kind == option, onClick = { kind = option; name = when(option) { ProviderKind.CODEX -> "Codex 订阅"; ProviderKind.DEEPSEEK -> "DeepSeek"; else -> "自定义供应商" }; baseUrl = if (option == ProviderKind.DEEPSEEK) "https://api.deepseek.com/v1" else if (option == ProviderKind.CODEX) CodexBackend.BASE_URL else "https://api.openai.com/v1" })
                    Text(kindLabel(option), style = MaterialTheme.typography.bodyMedium)
                }
            }
            OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
            if (kind != ProviderKind.CODEX) {
                OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("API Base URL") }, supportingText = { Text("HTTPS 地址，包含 /v1 等接口前缀") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(key, { key = it }, label = { Text("API Key") }, visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { IconButton(onClick = { reveal = !reveal }) { Icon(Icons.Default.Visibility, "显示或隐藏 API Key") } }, singleLine = true)
            } else Text("通过 OpenAI 设备授权登录 ChatGPT 订阅。授权令牌加密保存在本机，不会分享给局域网客户端。")
            if (existing != null) TextButton(enabled = !saving, onClick = { delete = true }) { Text("删除供应商及其路由", color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !saving && name.isNotBlank() && (kind == ProviderKind.CODEX || (key.isNotBlank() && validUrl)), onClick = { onSave(ProviderConfig(existing?.id ?: UUID.randomUUID().toString(), name.trim(), kind, if (kind == ProviderKind.CODEX) CodexBackend.BASE_URL else baseUrl.trim().trimEnd('/'), if (kind == ProviderKind.CODEX) "" else key.trim(), existing?.enabled ?: true, existing?.codexAccountId ?: "")) }) { Text(if (saving) "正在保存…" else "保存") } }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") } })
    if (delete && existing != null) AlertDialog(onDismissRequest = { if (!saving) delete = false }, title = { Text("删除 ${existing.name}？") }, text = { Text("该供应商的本机凭据和关联路由将一并移除。") }, confirmButton = { TextButton(enabled = !saving, onClick = { onDelete(existing) }) { Text(if (saving) "正在删除…" else "删除") } }, dismissButton = { TextButton(enabled = !saving, onClick = { delete = false }) { Text("保留") } })
}

@Composable private fun RouteDialog(existing: RouteConfig?, config: RouterConfig, saving: Boolean, onDismiss: () -> Unit, onSave: (RouteConfig) -> Unit, onDelete: (RouteConfig) -> Unit) {
    var providerId by remember { mutableStateOf(existing?.providerId ?: config.providers.firstOrNull()?.id.orEmpty()) }
    val selected = config.providers.find { it.id == providerId }
    var alias by remember { mutableStateOf(existing?.alias ?: if (selected?.kind == ProviderKind.DEEPSEEK) "deepseek-chat" else "codex") }
    var model by remember { mutableStateOf(existing?.upstreamModel ?: if (selected?.kind == ProviderKind.DEEPSEEK) "deepseek-chat" else "") }
    var menu by remember { mutableStateOf(false) }
    val duplicate = config.routes.any { it.alias == alias.trim() && it.alias != existing?.alias }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "添加模型路由" else "编辑模型路由") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box {
                OutlinedButton(onClick = { menu = true }) { Text(selected?.name ?: "选择供应商"); Icon(Icons.Default.ExpandMore, null) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) { config.providers.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { providerId = p.id; menu = false }) } }
            }
            OutlinedTextField(alias, { alias = it }, label = { Text("共享别名") }, supportingText = { Text(if (duplicate) "别名已存在" else "客户端请求中的 model") }, isError = duplicate, singleLine = true)
            OutlinedTextField(model, { model = it }, label = { Text("上游模型 ID") }, supportingText = { Text(if (selected?.kind == ProviderKind.CODEX) "填写订阅可用的 Codex 模型，例如 gpt-5.1-codex" else "例如 deepseek-chat 或 deepseek-reasoner") }, singleLine = true)
            if (existing != null) TextButton(enabled = !saving, onClick = { onDelete(existing) }) { Text("删除路由", color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !saving && selected != null && alias.trim().matches(Regex("[A-Za-z0-9._:/-]{1,128}")) && model.isNotBlank() && !duplicate, onClick = { onSave(RouteConfig(alias.trim(), providerId, model.trim(), existing?.enabled ?: true)) }) { Text(if (saving) "正在保存…" else "保存") } }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") } })
}

@Composable private fun Settings(config: RouterConfig, running: Boolean, saving: Boolean, onPort: (Int) -> Unit, onRotate: (() -> Unit) -> Unit) {
    var port by remember(config.port) { mutableStateOf(config.port.toString()) }
    var rotate by remember { mutableStateOf(false) }
    Heading("共享设置", "控制入口，保护账号。")
    OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, enabled = !running, label = { Text("监听端口") }, supportingText = { Text(if (running) "停止共享后可修改" else "1024–65535，默认 8317") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
    OutlinedButton(onClick = { port.toIntOrNull()?.let(onPort) }, enabled = !running && !saving && (port.toIntOrNull() ?: 0) in 1024..65535 && port != config.port.toString()) { Text("保存端口") }
    HorizontalDivider()
    SectionTitle("访问与隐私")
    Text("供应商密钥和订阅令牌使用 Android Keystore 加密保存。局域网访问使用独立随机密钥；应用不会把上游凭据返回给客户端。", style = MaterialTheme.typography.bodyMedium)
    OutlinedButton(enabled = !saving, onClick = { rotate = true }) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("重置局域网密钥") }
    Notice("后台共享", "共享期间显示常驻通知，可随时停止。部分系统需要在电池设置中允许后台运行；应用不会自动开机启动。")
    Text("PathPilot 0.1.0 · 原生 Android\nResponses / Chat Completions / Models\n账号留在手机 · 客户端使用独立密钥", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (rotate) AlertDialog(onDismissRequest = { if (!saving) rotate = false }, title = { Text("重置访问密钥？") }, text = { Text("旧密钥会立即失效，需要在其他设备上更新配置。正在处理的请求不会被中断。") }, confirmButton = { TextButton(enabled = !saving, onClick = { onRotate { rotate = false } }) { Text(if (saving) "正在保存…" else "重置") } }, dismissButton = { TextButton(enabled = !saving, onClick = { rotate = false }) { Text("取消") } })
}

@Composable private fun CodexLoginDialog(provider: ProviderConfig, store: AppStore, onDismiss: () -> Unit, onSuccess: () -> Unit) {
    val context = LocalContext.current
    val loginModel: CodexLoginViewModel = viewModel()
    val state by loginModel.state.collectAsStateWithLifecycle()
    val savingTokens = state.phase == CodexLoginPhase.SAVING
    LaunchedEffect(provider.id) { loginModel.begin(provider.id, store) }
    LaunchedEffect(state.phase, state.providerId) {
        if (state.phase == CodexLoginPhase.COMPLETE && state.providerId == provider.id) {
            loginModel.cancel()
            onSuccess()
        }
    }
    fun close() { if (!savingTokens) { loginModel.cancel(); onDismiss() } }
    AlertDialog(onDismissRequest = ::close, title = { Text("登录 ChatGPT / Codex") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (state.phase) {
                CodexLoginPhase.AUTH_ERROR, CodexLoginPhase.SAVE_ERROR -> Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                CodexLoginPhase.SAVING, CodexLoginPhase.COMPLETE -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth()); Text("授权已完成，正在加密保存到本机…")
                }
                CodexLoginPhase.POLLING -> state.challenge?.let { challenge ->
                    Text("在 OpenAI 官方页面登录，并输入以下一次性代码。完成后返回此处，应用会自动保存授权。")
                    CopyRow("设备授权码", challenge.userCode, { copy(context, "设备授权码", it) })
                    Button(onClick = { try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(challenge.verificationUrl))) } catch (_: Exception) { loginModel.browserUnavailable() } }, modifier = Modifier.fillMaxWidth()) { Text("打开 OpenAI 授权页面"); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Filled.OpenInNew, null) }
                    Text("若页面提示需要启用设备代码登录，请在 ChatGPT 安全设置中开启后重试。", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("等待授权 · 关闭可取消此次登录", style = MaterialTheme.typography.bodySmall)
                }
                else -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在向 OpenAI 获取设备授权码…") }
            }
        }
    }, confirmButton = {
        if (state.phase == CodexLoginPhase.SAVE_ERROR) TextButton(onClick = loginModel::retrySave) { Text("重试保存") }
        else if (state.phase == CodexLoginPhase.AUTH_ERROR) TextButton(onClick = loginModel::retryAuthorization) { Text("重新授权") }
    }, dismissButton = { TextButton(enabled = !savingTokens, onClick = ::close) { Text("关闭") } })
}
