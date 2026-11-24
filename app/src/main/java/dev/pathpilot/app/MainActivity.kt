package dev.pathpilot.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.core.content.ContextCompat
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
                Text("无法读取配置，请重试")
                TextButton(onClick = { attempt++ }) { Text("重试") }
            } else {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("正在加载…")
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
    var addProvider by rememberSaveable { mutableStateOf(false) }
    var routeEditorAlias by rememberSaveable { mutableStateOf<String?>(null) }
    var addRoute by rememberSaveable { mutableStateOf(false) }
    var routeProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    var codexLoginProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var notificationPending by rememberSaveable { mutableStateOf(false) }
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
            } catch (error: Exception) {
                android.util.Log.e("PathPilot", "Configuration save failed: ${error.javaClass.simpleName}\n${error.stackTrace.joinToString("\n")}")
                saveError = when (error) {
                    is IllegalArgumentException -> error.message ?: "配置无效"
                    is java.io.IOException -> "无法写入配置，请重试"
                    else -> "保存失败，请重试"
                }
            } finally { saving = false }
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (notificationPending) {
            notificationPending = false
            RouterService.start(context)
        }
    }
    fun setSharing(enabled: Boolean) {
        if (!enabled) {
            notificationPending = false
            RouterService.stop(context)
        } else if (!runtime.running && !runtime.starting && !runtime.stopping && !notificationPending) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPending = true
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else RouterService.start(context)
        }
    }
    fun newRoute(providerId: String? = null) {
        if (config.providers.isEmpty()) { addProvider = true; return }
        routeProviderId = providerId
        addRoute = true
    }
    val tabs = listOf("共享", "供应商", "路由", "设置")
    val icons = listOf(PathPilotIcons.Share, PathPilotIcons.Providers, PathPilotIcons.Routes, PathPilotIcons.Settings)
    Scaffold(
        topBar = { TopAppBar(title = { Text(tabs[tab]) }, actions = {
            if ((tab == 1 && config.providers.isNotEmpty()) || (tab == 2 && config.routes.isNotEmpty())) {
                IconButton(enabled = !saving, onClick = { if (tab == 1) addProvider = true else newRoute() }) {
                    Icon(PathPilotIcons.Add, if (tab == 1) "添加供应商" else "添加路由")
                }
            }
        }) },
        bottomBar = { NavigationBar { tabs.forEachIndexed { index, label ->
            NavigationBarItem(selected = tab == index, onClick = { tab = index },
                icon = { Icon(icons[index], null) }, label = { Text(label) })
        } } },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            storageError?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
            when (tab) {
                0 -> SharingScreen(config, runtime, sessions, saving || storageError != null, notificationPending,
                    onSharingChange = ::setSharing, onProviders = { tab = 1; if (config.providers.isEmpty()) addProvider = true }, onRoutes = { tab = 2; if (config.routes.isEmpty()) newRoute() },
                    onCopy = { label, value -> copy(context, label, value); notify("已复制") })
                1 -> ProvidersScreen(config, sessions, saving,
                    onAdd = { addProvider = true }, onEdit = { providerEditorId = it.id },
                    onToggle = { provider, enabled -> update { c -> c.copy(providers = c.providers.map { if (it.id == provider.id) it.copy(enabled = enabled) else it }) } },
                    onLogin = { codexLoginProviderId = it.id }, onRoute = { newRoute(it.id) })
                2 -> RoutesScreen(config, saving, onAdd = { newRoute() }, onEdit = { routeEditorAlias = it.alias },
                    onToggle = { route, enabled -> update { c -> c.copy(routes = c.routes.map { if (it.alias == route.alias) it.copy(enabled = enabled) else it }) } })
                3 -> SettingsScreen(config, runtime.running || runtime.starting || runtime.stopping || notificationPending, saving,
                    onPort = { port, done -> update(onSuccess = done) { it.copy(port = port) } },
                    onProxy = { proxy, done -> update(onSuccess = done) { it.copy(outboundProxy = proxy) } },
                    onRotate = { done -> update(onSuccess = { done(); notify("密钥已更新") }) { it.copy(accessKey = AccessKeys.generate()) } })
            }
        }
    }
    val providerEditor = config.providers.find { it.id == providerEditorId }
    if (addProvider || providerEditor != null) ProviderEditor(existing = providerEditor, saving = saving, proxy = config.outboundProxy,
        onDismiss = { if (!saving) { addProvider = false; providerEditorId = null } },
        onSave = { provider, models ->
            update(onSuccess = {
                addProvider = false; providerEditorId = null
                tab = 1
                if (provider.kind == ProviderKind.CODEX && provider.id !in store.authenticatedCodexProviders.value) codexLoginProviderId = provider.id
                else notify(if (models.isEmpty()) "已保存" else "供应商与路由已保存")
            }) { ProviderRoutes.addSelected(it, provider, models) }
        },
        onDelete = { provider -> update(onSuccess = { providerEditorId = null }) { c ->
            c.copy(providers = c.providers.filterNot { it.id == provider.id }, routes = c.routes.filterNot { it.providerId == provider.id })
        } },
    )
    val routeEditor = config.routes.find { it.alias == routeEditorAlias }
    if (addRoute || routeEditor != null) RouteDialog(routeEditor, config, saving, routeProviderId,
        onDismiss = { if (!saving) { addRoute = false; routeEditorAlias = null; routeProviderId = null } },
        onSave = { route -> update(onSuccess = { addRoute = false; routeEditorAlias = null; routeProviderId = null }) { c ->
            c.copy(routes = c.routes.filterNot { it.alias == routeEditorAlias } + route)
        } },
        onDelete = { route -> update(onSuccess = { routeEditorAlias = null }) { c -> c.copy(routes = c.routes.filterNot { it.alias == route.alias }) } },
    )
    config.providers.find { it.id == codexLoginProviderId }?.let { provider ->
        CodexLoginDialog(provider, store, onDismiss = { codexLoginProviderId = null }, onSuccess = { codexLoginProviderId = null; notify("已登录") })
    }
    saveError?.let { message -> AlertDialog(onDismissRequest = { saveError = null }, title = { Text("未能保存") },
        text = { Text(message) }, confirmButton = { TextButton(onClick = { saveError = null }) { Text("返回编辑") } }) }
}

@Composable private fun CopyRow(label: String, value: String, onCopy: (String) -> Unit, secret: Boolean = false) {
    var visible by remember { mutableStateOf(false) }
    OutlinedCard {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (secret && !visible) "••••••••••••••••" else value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
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
                    LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在保存授权…")
                }
                CodexLoginPhase.POLLING -> state.challenge?.let { challenge ->
                    Text("在浏览器中输入授权码，完成登录后返回。")
                    CopyRow("设备授权码", challenge.userCode, { copy(context, "设备授权码", it) })
                    Button(onClick = { try { context.startActivity(Intent(Intent.ACTION_VIEW, challenge.verificationUrl.toUri())) } catch (_: Exception) { loginModel.browserUnavailable() } }, modifier = Modifier.fillMaxWidth()) { Text("打开登录页面"); Spacer(Modifier.width(8.dp)); Icon(PathPilotIcons.OpenInNew, null) }
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("等待登录…", style = MaterialTheme.typography.bodySmall)
                }
                else -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在获取授权码…") }
            }
        }
    }, confirmButton = {
        if (state.phase == CodexLoginPhase.SAVE_ERROR) TextButton(onClick = loginModel::retrySave) { Text("重试保存") }
        else if (state.phase == CodexLoginPhase.AUTH_ERROR) TextButton(onClick = loginModel::retryAuthorization) { Text("重新授权") }
    }, dismissButton = { TextButton(enabled = !savingTokens, onClick = ::close) { Text("关闭") } })
}
