package dev.pathpilot.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pathpilot.app.runtime.*
import dev.pathpilot.app.ui.theme.PathPilotTheme
import dev.pathpilot.app.ui.icons.PathPilotIcons
import dev.pathpilot.app.ui.providers.ProviderEditor
import dev.pathpilot.core.providers.ProviderSetup
import dev.pathpilot.core.providers.ProviderRoutes
import dev.pathpilot.core.*
import dev.pathpilot.core.codex.*
import kotlinx.coroutines.*

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
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var providerEditorId by rememberSaveable { mutableStateOf<String?>(null) }
    val providerEditor = config.providers.firstOrNull { it.id == providerEditorId }
    var addProvider by rememberSaveable { mutableStateOf(false) }
    var routeEditor by remember { mutableStateOf<RouteConfig?>(null) }
    var addRoute by rememberSaveable { mutableStateOf(false) }
    var routeProviderId by rememberSaveable { mutableStateOf<String?>(null) }
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
    fun suggestRoute(provider: ProviderConfig) {
        scope.launch {
            if (snackbar.showSnackbar("${provider.name} 已保存", actionLabel = "添加路由", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                routeProviderId = provider.id
                addRoute = true
                tab = 2
            }
        }
    }
    val pageScroll = remember(tab) { ScrollState(0) }
    val tabs = listOf("共享", "供应商", "路由", "设置")
    val icons = listOf(PathPilotIcons.Share, PathPilotIcons.Providers, PathPilotIcons.Routes, PathPilotIcons.Settings)
    Scaffold(
        topBar = { TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(PathPilotIcons.Brand, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp)); Text("PathPilot", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        } }, actions = { Text("本机路由", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 20.dp)) }) },
        bottomBar = { NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) { tabs.forEachIndexed { i, label ->
            NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icons[i], label) }, label = { Text(label) }, colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer, selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer, selectedTextColor = MaterialTheme.colorScheme.primary, unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
        } } },
        snackbarHost = { SnackbarHost(snackbar) },

    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(pageScroll).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            storageError?.let { Notice("凭据存储不可用", "为保护已有凭据，已阻止覆盖和启动。请查看设备存储状态；不要清除应用数据。") }
            when (tab) {
                0 -> {
                    Heading("让手机成为 AI 入口", "统一账号与模型，分享给身边的设备。")
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = MaterialTheme.shapes.extraLarge) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(PathPilotIcons.Share, null, Modifier.size(28.dp))
                                Spacer(Modifier.width(12.dp))
                                Column { Text(if (runtime.running) "局域网共享已开启" else "准备好，随时连接", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    Text(if (runtime.running) "保持手机与客户端连接到同一网络" else "开启后，由手机接收并转发 API 请求", style = MaterialTheme.typography.bodySmall) }
                            }
                            Button(shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight), contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight), onClick = {
                                if (runtime.running) RouterService.stop(context)
                                else if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else RouterService.start(context)
                            }, enabled = runtime.running || (!saving && storageError == null && config.routes.any { r -> r.enabled && config.providers.any { it.id == r.providerId && it.enabled && (it.kind != ProviderKind.CODEX || it.id in sessions) } }), modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight)) {
                                Icon(if (runtime.running) PathPilotIcons.Stop else PathPilotIcons.Play, null)
                                Spacer(Modifier.width(8.dp)); Text(if (runtime.running) "停止共享" else "开启共享", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
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
                            TextButton(onClick = { tab = 1 }) { Text("配置供应商"); Icon(PathPilotIcons.Add, null) }
                        } }
                    }
                    if (runtime.recentRequests.isNotEmpty()) {
                        SectionTitle("最近请求")
                        runtime.recentRequests.take(12).forEach { event ->
                            ListItem(content = { Text(event.model.ifBlank { "未识别模型" }) }, supportingContent = { Text("${event.endpoint} · ${event.durationMs} ms") }, trailingContent = { Text(event.status.toString(), color = if (event.status < 400) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) })
                        }
                        Text("仅记录状态、模型和耗时，不记录提示词或回复。", style = MaterialTheme.typography.bodySmall)
                    }
                }
                1 -> {
                    Heading("供应商", "集中管理订阅账号与 API 服务。")
                    PageAction("添加供应商", !saving) { addProvider = true }
                    if (config.providers.isEmpty()) EmptyState(PathPilotIcons.Providers, "还没有供应商", "添加 ChatGPT / Codex 订阅，或填写 DeepSeek API Key。")
                    config.providers.forEach { provider ->
                        OutlinedCard(onClick = { if (!saving) providerEditorId = provider.id }) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (provider.kind == ProviderKind.CODEX) PathPilotIcons.Account else PathPilotIcons.Api, null, Modifier.size(30.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) { Text(provider.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(kindLabel(provider.kind), style = MaterialTheme.typography.bodySmall) }
                                    Switch(checked = provider.enabled, enabled = !saving, onCheckedChange = { enabled -> update { c -> c.copy(providers = c.providers.map { if (it.id == provider.id) it.copy(enabled = enabled) else it }) } })
                                }
                                if (provider.kind == ProviderKind.CODEX) {
                                    Text(if (provider.id in sessions) "已保存订阅授权" else "尚未登录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                    OutlinedButton(onClick = { codexLoginProviderId = provider.id }) { Text(if (provider.id in sessions) "重新登录" else "登录 ChatGPT 订阅") }
                                } else Text(provider.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${config.routes.count { it.providerId == provider.id }} 条模型路由", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    TextButton(enabled = !saving, onClick = { routeProviderId = provider.id; addRoute = true }) {
                                        Icon(PathPilotIcons.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("添加路由")
                                    }
                                }
                            }
                        }
                    }
                }
                2 -> {
                    Heading("模型路由", "客户端使用别名，手机负责选择上游。")
                    PageAction(if (config.providers.isEmpty()) "先添加供应商" else "添加路由", !saving) {
                        if (config.providers.isEmpty()) addProvider = true else { routeProviderId = null; addRoute = true }
                    }
                    if (config.routes.isEmpty()) EmptyState(PathPilotIcons.Routes, "创建第一条路由", "为上游模型设置共享名称，\n让其他设备通过手机调用。")
                    config.routes.forEach { route ->
                        OutlinedCard(onClick = { routeEditor = route }) {
                            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(route.alias, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("${config.providers.find { it.id == route.providerId }?.name ?: "供应商已移除"} → ${route.upstreamModel}", style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = route.enabled, enabled = !saving, onCheckedChange = { enabled -> update { c -> c.copy(routes = c.routes.map { if (it.alias == route.alias) it.copy(enabled = enabled) else it }) } })
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
    if (addProvider || providerEditor != null) ProviderEditor(providerEditor, saving,
        onDismiss = { if (!saving) { addProvider = false; providerEditorId = null } },
        onSave = { provider, selectedModels ->
            update(onSuccess = {
                addProvider = false; providerEditorId = null
                if (provider.kind == ProviderKind.CODEX && provider.id !in store.authenticatedCodexProviders.value) {
                    codexLoginProviderId = provider.id
                } else if (selectedModels.isEmpty()) suggestRoute(provider)
                else notify("供应商与模型路由已保存")
            }) { c -> ProviderRoutes.addSelected(c, provider, selectedModels) }
        },
        onDelete = { provider ->
            update(onSuccess = { providerEditorId = null }) { c -> c.copy(providers = c.providers.filterNot { it.id == provider.id }, routes = c.routes.filterNot { it.providerId == provider.id }) }
        },
    )
    if (addRoute || routeEditor != null) RouteDialog(routeEditor, config, saving, routeProviderId, onDismiss = { if (!saving) { addRoute = false; routeEditor = null; routeProviderId = null } }, onSave = { route ->
        val oldAlias = routeEditor?.alias
        update(onSuccess = { addRoute = false; routeEditor = null; routeProviderId = null }) { c -> c.copy(routes = c.routes.filterNot { it.alias == oldAlias } + route) }
    }, onDelete = { route -> update(onSuccess = { routeEditor = null }) { c -> c.copy(routes = c.routes.filterNot { it.alias == route.alias }) } })
    codexLogin?.let { provider -> CodexLoginDialog(provider, store, onDismiss = { codexLoginProviderId = null }, onSuccess = { codexLoginProviderId = null; suggestRoute(provider) }) }
    saveError?.let { message ->
        AlertDialog(onDismissRequest = { saveError = null }, title = { Text("保存失败") },
            text = { Text(message) }, confirmButton = { TextButton(onClick = { saveError = null }) { Text("返回编辑") } })
    }
}

@Composable private fun Heading(title: String, subtitle: String) {
    Column(Modifier.padding(top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun PageAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, shapes = ButtonDefaults.shapes(), enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Icon(PathPilotIcons.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(label)
    }
}
@Composable private fun SectionTitle(text: String) { Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
@Composable private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium); Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
@Composable private fun Notice(title: String, body: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall); Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable private fun EmptyState(icon: ImageVector, title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
            Icon(icon, null, Modifier.padding(18.dp).size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
@Composable private fun CopyRow(label: String, value: String, onCopy: (String) -> Unit, secret: Boolean = false) {
    var visible by remember { mutableStateOf(false) }
    OutlinedCard {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (secret && !visible) "pp_••••••••••••••••" else value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                if (secret) IconButton(onClick = { visible = !visible }) { Icon(if (visible) PathPilotIcons.VisibilityOff else PathPilotIcons.Visibility, if (visible) "隐藏密钥" else "显示密钥") }
                IconButton(onClick = { onCopy(value) }) { Icon(PathPilotIcons.Copy, "复制$label") }
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

@Composable private fun RouteDialog(existing: RouteConfig?, config: RouterConfig, saving: Boolean, initialProviderId: String?, onDismiss: () -> Unit, onSave: (RouteConfig) -> Unit, onDelete: (RouteConfig) -> Unit) {
    var providerId by remember { mutableStateOf(existing?.providerId ?: initialProviderId ?: config.providers.firstOrNull()?.id.orEmpty()) }
    val selected = config.providers.find { it.id == providerId }
    fun defaultAlias(kind: ProviderKind?) = when (kind) { ProviderKind.DEEPSEEK -> "deepseek-chat"; ProviderKind.CODEX -> "codex"; else -> "" }
    fun defaultModel(kind: ProviderKind?) = if (kind == ProviderKind.DEEPSEEK) "deepseek-chat" else ""
    var alias by remember { mutableStateOf(existing?.alias ?: defaultAlias(selected?.kind)) }
    var model by remember { mutableStateOf(existing?.upstreamModel ?: defaultModel(selected?.kind)) }
    var menu by remember { mutableStateOf(false) }
    val duplicate = config.routes.any { it.alias == alias.trim() && it.alias != existing?.alias }
    val modelError = ProviderSetup.validateModelId(model)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "添加模型路由" else "编辑模型路由") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box {
                OutlinedButton(enabled = !saving, onClick = { menu = true }) { Text(selected?.name ?: "选择供应商"); Icon(PathPilotIcons.ExpandMore, null) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) { config.providers.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = {
                    if (existing == null) {
                        if (alias == defaultAlias(selected?.kind)) alias = defaultAlias(p.kind)
                        if (model == defaultModel(selected?.kind)) model = defaultModel(p.kind)
                    }
                    providerId = p.id; menu = false
                }) } }
            }
            OutlinedTextField(alias, { alias = it }, enabled = !saving, label = { Text("共享别名") }, supportingText = { Text(if (duplicate) "别名已存在" else "客户端请求中的 model") }, isError = duplicate, singleLine = true)
            OutlinedTextField(model, { model = it }, enabled = !saving, label = { Text("上游模型 ID") }, supportingText = { Text(if (model.isNotBlank() && modelError != null) modelError else if (selected?.kind == ProviderKind.CODEX) "填写订阅可用的 Codex 模型 ID" else "填写供应商支持的模型 ID") }, isError = model.isNotBlank() && modelError != null, singleLine = true)
            if (existing != null) TextButton(enabled = !saving, onClick = { onDelete(existing) }) { Text("删除路由", color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !saving && selected != null && alias.trim().matches(Regex("[A-Za-z0-9._:/-]{1,128}")) && modelError == null && !duplicate, onClick = { onSave(RouteConfig(alias.trim(), providerId, model.trim(), existing?.enabled ?: true)) }) { Text(if (saving) "正在保存…" else "保存") } }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") } })
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
    OutlinedButton(enabled = !saving, onClick = { rotate = true }) { Icon(PathPilotIcons.Refresh, null); Spacer(Modifier.width(8.dp)); Text("重置局域网密钥") }
    Notice("后台共享", "共享期间显示常驻通知，可随时停止。部分系统需要在电池设置中允许后台运行；应用不会自动开机启动。")
    Text("PathPilot ${BuildConfig.VERSION_NAME} · 原生 Android\nResponses / Chat Completions / Models\n账号留在手机 · 客户端使用独立密钥", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Button(onClick = { try { context.startActivity(Intent(Intent.ACTION_VIEW, challenge.verificationUrl.toUri())) } catch (_: Exception) { loginModel.browserUnavailable() } }, modifier = Modifier.fillMaxWidth()) { Text("打开 OpenAI 授权页面"); Spacer(Modifier.width(8.dp)); Icon(PathPilotIcons.OpenInNew, null) }
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
