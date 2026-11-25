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
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalResources
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
import dev.pathpilot.app.settings.AppPreferences
import dev.pathpilot.app.McpPage
import dev.pathpilot.app.ui.theme.PathPilotTheme
import dev.pathpilot.app.ui.i18n.localize
import dev.pathpilot.app.ui.icons.PathPilotIcons
import dev.pathpilot.app.ui.providers.ProviderEditor
import dev.pathpilot.core.providers.ProviderSetup
import dev.pathpilot.core.providers.ProviderRoutes
import dev.pathpilot.core.*
import dev.pathpilot.core.codex.*
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    private val openMcp = mutableStateOf(false)
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLanguageController.wrapContext(newBase)) }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_mcp", false)) openMcp.value = true
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openMcp.value = intent.getBooleanExtra("open_mcp", false)
        setContent { PathPilotApp(openMcp.value, onMcpIntentHandled = { openMcp.value = false; intent.removeExtra("open_mcp") }) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PathPilotApp(openMcp: Boolean, onMcpIntentHandled: () -> Unit) {
    val strings = LocalResources.current
    val context = LocalContext.current.applicationContext
    var store by remember { mutableStateOf<AppStore?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        loadFailed = false
        try {
            store = withContext(Dispatchers.IO) {
                AppStore.get(context).also { loaded ->
                    if (Build.VERSION.SDK_INT >= 33 && loaded.storageError.value == null) {
                        val selected = AppLanguageController.current(context)
                        if (selected != loaded.preferences.value.language) loaded.setPreferences(loaded.preferences.value.copy(language = selected))
                    }
                }
            }
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { loadFailed = true }
    }
    val preferences = store?.preferences?.collectAsStateWithLifecycle()?.value ?: AppPreferences()
    PathPilotTheme(preferences) {
    if (store != null) PathPilotContent(requireNotNull(store), openMcp, onMcpIntentHandled)
    else Surface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("PathPilot", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            if (loadFailed) {
                Text(strings.getString(R.string.ui_config_load_failed))
                TextButton(onClick = { attempt++ }) { Text(strings.getString(R.string.ui_retry)) }
            } else {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(strings.getString(R.string.ui_loading))
            }
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PathPilotContent(store: AppStore, openMcp: Boolean, onMcpIntentHandled: () -> Unit) {
    val strings = LocalResources.current
    val context = LocalContext.current
    val config by store.config.collectAsStateWithLifecycle()
    val runtime by RouterRuntime.state.collectAsStateWithLifecycle()
    val storageError by store.storageError.collectAsStateWithLifecycle()
    val sessions by store.authenticatedCodexProviders.collectAsStateWithLifecycle()
    val history by store.requestHistory.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var mcpVisible by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openMcp) {
        if (openMcp) { tab = 4; mcpVisible = true; onMcpIntentHandled() }
    }
    BackHandler(enabled = tab == 4 && mcpVisible) { mcpVisible = false }
    var statisticsReturnTab by rememberSaveable { mutableStateOf<Int?>(null) }
    var statisticsEntry by rememberSaveable { mutableIntStateOf(0) }
    var statisticsRequests by rememberSaveable { mutableStateOf(false) }
    var statisticsProvider by rememberSaveable { mutableStateOf<String?>(null) }
    var statisticsModel by rememberSaveable { mutableStateOf<String?>(null) }
    var statisticsUpstreamModel by rememberSaveable { mutableStateOf<String?>(null) }
    var statisticsRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    var providerEditorId by rememberSaveable { mutableStateOf<String?>(null) }
    var fetchModelsOnOpen by rememberSaveable { mutableStateOf(false) }
    var addProvider by rememberSaveable { mutableStateOf(false) }
    var routeEditorAlias by rememberSaveable { mutableStateOf<String?>(null) }
    var addRoute by rememberSaveable { mutableStateOf(false) }
    var routeProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    var modelProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    var codexLoginProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var notificationPending by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun notify(message: String) { scope.launch { snackbar.showSnackbar(strings.localize(message)) } }
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
                    is IllegalArgumentException -> error.message ?: strings.getString(R.string.ui_invalid_config)
                    is java.io.IOException -> strings.getString(R.string.ui_config_write_failed)
                    else -> strings.getString(R.string.ui_save_failed_retry)
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
    fun openStatistics(requests: Boolean = false, providerId: String? = null, model: String? = null,
                     upstreamModel: String? = null, requestId: String? = null) {
        statisticsRequests = requests
        statisticsProvider = providerId
        statisticsModel = model
        statisticsUpstreamModel = upstreamModel
        statisticsRequestId = requestId
        statisticsReturnTab = tab.takeUnless { it == 3 }
        statisticsEntry++
        tab = 3
    }
    fun resetStatistics() {
        statisticsRequests = false
        statisticsProvider = null
        statisticsModel = null
        statisticsUpstreamModel = null
        statisticsRequestId = null
        statisticsReturnTab = null
        statisticsEntry++
    }
    fun leaveStatistics() {
        tab = statisticsReturnTab ?: 0
        statisticsReturnTab = null
    }
    BackHandler(enabled = tab == 3) { leaveStatistics() }
    val tabs = listOf(strings.getString(R.string.main_share_tab), strings.getString(R.string.ui_providers), strings.getString(R.string.ui_models), strings.getString(R.string.ui_statistics_tab), strings.getString(R.string.ui_settings))
    val icons = listOf(PathPilotIcons.Share, PathPilotIcons.Providers, PathPilotIcons.Routes, PathPilotIcons.Usage, PathPilotIcons.Settings)
    Scaffold(
        topBar = { if (tab != 2 && tab != 3 && !(tab == 4 && mcpVisible)) TopAppBar(title = { Text(tabs[tab]) }, actions = {
            if (tab == 1 && config.providers.isNotEmpty()) {
                IconButton(enabled = !saving, onClick = { addProvider = true }) {
                    Icon(PathPilotIcons.Add, strings.getString(R.string.ui_add_provider))
                }
            }
        }) },
        bottomBar = { NavigationBar { tabs.forEachIndexed { index, label ->
            NavigationBarItem(selected = tab == index, onClick = { if (index != tab) { if (index == 3) resetStatistics(); tab = index } },
                icon = { Icon(icons[index], null) },
                label = { Text(label, Modifier.fillMaxWidth(), textAlign = TextAlign.Center) })
        } } },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            storageError?.let { Text(strings.localize(it), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
            when (tab) {
                0 -> SharingScreen(config, runtime, sessions, saving || storageError != null, notificationPending,
                    onSharingChange = ::setSharing, onProviders = { tab = 1; if (config.providers.isEmpty()) addProvider = true }, onRoutes = { modelProviderId = null; tab = 2 },
                    history = history, onRequests = { openStatistics(requests = true) },
                    onRequest = { openStatistics(requests = true, requestId = it.id) },
                    onCopy = { label, value -> copy(context, label, value, secret = value == config.accessKey); notify(strings.getString(R.string.ui_copied)) })
                1 -> ProvidersScreen(config, sessions, saving,
                    onAdd = { fetchModelsOnOpen = false; addProvider = true }, onEdit = { fetchModelsOnOpen = false; providerEditorId = it.id },
                    onToggle = { provider, enabled -> update { c -> c.copy(providers = c.providers.map { if (it.id == provider.id) it.copy(enabled = enabled) else it }) } },
                    onLogin = { codexLoginProviderId = it.id }, onRoute = { newRoute(it.id) },
                    onModels = { providerEditorId = it.id; fetchModelsOnOpen = true },
                    onManageModels = { modelProviderId = it.id; tab = 2 })
                2 -> ModelRoutesPage(config, saving, modelProviderId, onSelectProvider = { modelProviderId = it },
                    onAddProvider = { fetchModelsOnOpen = false; addProvider = true }, onAddModel = { newRoute(it) },
                    onEditModel = { routeEditorAlias = it.alias },
                    requestEvents = history.events,
                    onViewUsage = { provider, route -> openStatistics(providerId = provider.id, model = route?.alias, upstreamModel = route?.upstreamModel) },
                    onModelToggle = { route, enabled -> update { c -> c.copy(routes = c.routes.map { if (it.alias == route.alias) it.copy(enabled = enabled) else it }) } },
                    onProviderToggle = { provider, enabled -> update { c -> c.copy(providers = c.providers.map { if (it.id == provider.id) it.copy(enabled = enabled) else it }) } })
                3 -> key(statisticsEntry) { StatisticsPage(
                    events = history.events, error = history.error,
                    initialProviderId = statisticsProvider, initialModel = statisticsModel,
                    initialUpstreamModel = statisticsUpstreamModel,
                    initialRequests = statisticsRequests, initialRequestId = statisticsRequestId,
                    onBack = if (statisticsReturnTab != null) ({ leaveStatistics() }) else null,
                    onClear = { scope.launch { withContext(Dispatchers.IO) { store.requestHistory.clear() } } },
                    onRetry = { scope.launch { withContext(Dispatchers.IO) { store.requestHistory.reload() } } },
                ) }
                4 -> if (mcpVisible) McpPage(store, onBack = { mcpVisible = false })
                    else SettingsScreen(store, onMcp = { mcpVisible = true }, onMessage = ::notify)
            }
        }
    }
    val providerEditor = config.providers.find { it.id == providerEditorId }
    if (addProvider || providerEditor != null) ProviderEditor(existing = providerEditor, saving = saving, proxy = config.outboundProxy,
        codexSignedIn = providerEditor?.id in sessions, resolveCodexCredentials = store::resolveCodexCredentials,
        onLogin = { codexLoginProviderId = providerEditor?.id }, fetchModelsOnOpen = fetchModelsOnOpen,
        onModelsFetchStarted = { fetchModelsOnOpen = false },
        onDismiss = { if (!saving) { addProvider = false; providerEditorId = null; fetchModelsOnOpen = false } },
        onSave = { provider, models ->
            update(onSuccess = {
                addProvider = false; providerEditorId = null; fetchModelsOnOpen = false
                tab = 1
                if (provider.kind == ProviderKind.CODEX && provider.id !in store.authenticatedCodexProviders.value) codexLoginProviderId = provider.id
                else notify(strings.getString(if (models.isEmpty()) R.string.ui_saved else R.string.ui_provider_models_saved))
            }) { ProviderRoutes.addSelected(it, provider, models) }
        },
        onDelete = { provider -> update(onSuccess = { providerEditorId = null; fetchModelsOnOpen = false }) { c ->
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
        CodexLoginDialog(provider, store, onDismiss = { codexLoginProviderId = null }, onSuccess = {
            codexLoginProviderId = null
            providerEditorId = provider.id
            fetchModelsOnOpen = true
            tab = 1
        })
    }
    saveError?.let { message -> AlertDialog(onDismissRequest = { saveError = null }, title = { Text(strings.getString(R.string.ui_save_failed)) },
        text = { Text(strings.localize(message)) }, confirmButton = { TextButton(onClick = { saveError = null }) { Text(strings.getString(R.string.ui_back_to_editing)) } }) }
}

@Composable private fun CopyRow(label: String, value: String, onCopy: (String) -> Unit) {
    val strings = LocalResources.current
    OutlinedCard {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
                IconButton(onClick = { onCopy(value) }) { Icon(PathPilotIcons.Copy, strings.getString(R.string.ui_copy_value, label)) }
            }
        }
    }
}
private fun copy(context: Context, label: String, value: String, secret: Boolean = false) {
    val clip = ClipData.newPlainText(label, value)
    if (Build.VERSION.SDK_INT >= 33 && secret) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
}
private fun kindLabel(kind: ProviderKind, strings: android.content.res.Resources) = when (kind) { ProviderKind.CODEX -> strings.getString(R.string.ui_codex_subscription); ProviderKind.DEEPSEEK -> "DeepSeek API"; ProviderKind.OPENAI_COMPATIBLE -> strings.getString(R.string.ui_openai_compatible) }

@Composable private fun RouteDialog(existing: RouteConfig?, config: RouterConfig, saving: Boolean, initialProviderId: String?, onDismiss: () -> Unit, onSave: (RouteConfig) -> Unit, onDelete: (RouteConfig) -> Unit) {
    val strings = LocalResources.current
    var providerId by remember { mutableStateOf(existing?.providerId ?: initialProviderId ?: config.providers.firstOrNull()?.id.orEmpty()) }
    val selected = config.providers.find { it.id == providerId }
    fun defaultAlias(kind: ProviderKind?) = when (kind) { ProviderKind.DEEPSEEK -> "deepseek-chat"; ProviderKind.CODEX -> "codex"; else -> "" }
    fun defaultModel(kind: ProviderKind?) = if (kind == ProviderKind.DEEPSEEK) "deepseek-chat" else ""
    var alias by remember { mutableStateOf(existing?.alias ?: defaultAlias(selected?.kind)) }
    var model by remember { mutableStateOf(existing?.upstreamModel ?: defaultModel(selected?.kind)) }
    var menu by remember { mutableStateOf(false) }
    val duplicate = config.routes.any { it.alias == alias.trim() && it.alias != existing?.alias }
    val modelError = ProviderSetup.validateModelId(model)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) strings.getString(R.string.ui_add_model) else strings.getString(R.string.ui_edit_model)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box {
                OutlinedButton(enabled = !saving, onClick = { menu = true }) { Text(selected?.name ?: strings.getString(R.string.ui_choose_provider)); Icon(PathPilotIcons.ExpandMore, null) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) { config.providers.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = {
                    if (existing == null) {
                        if (alias == defaultAlias(selected?.kind)) alias = defaultAlias(p.kind)
                        if (model == defaultModel(selected?.kind)) model = defaultModel(p.kind)
                    }
                    providerId = p.id; menu = false
                }) } }
            }
            OutlinedTextField(alias, { alias = it }, enabled = !saving, label = { Text(strings.getString(R.string.ui_shared_model_name)) }, supportingText = { Text(if (duplicate) strings.getString(R.string.ui_name_exists) else strings.getString(R.string.ui_model_request_field)) }, isError = duplicate, singleLine = true)
            OutlinedTextField(model, { model = it }, enabled = !saving, label = { Text(strings.getString(R.string.ui_upstream_model_id)) }, supportingText = { Text(if (model.isNotBlank() && modelError != null) strings.localize(modelError) else if (selected?.kind == ProviderKind.CODEX) strings.getString(R.string.ui_enter_codex_model) else strings.getString(R.string.ui_enter_provider_model)) }, isError = model.isNotBlank() && modelError != null, singleLine = true)
            if (existing != null) TextButton(enabled = !saving, onClick = { onDelete(existing) }) { Text(strings.getString(R.string.ui_delete_model), color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !saving && selected != null && alias.trim().matches(Regex("[A-Za-z0-9._:/-]{1,128}")) && modelError == null && !duplicate, onClick = { onSave(RouteConfig(alias.trim(), providerId, model.trim(), existing?.enabled ?: true)) }) { Text(if (saving) strings.getString(R.string.ui_saving) else strings.getString(R.string.ui_save)) } }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(strings.getString(R.string.ui_cancel)) } })
}

@Composable private fun CodexLoginDialog(provider: ProviderConfig, store: AppStore, onDismiss: () -> Unit, onSuccess: () -> Unit) {
    val strings = LocalResources.current
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
    AlertDialog(onDismissRequest = ::close, title = { Text(strings.getString(R.string.ui_sign_in_codex)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (state.phase) {
                CodexLoginPhase.AUTH_ERROR, CodexLoginPhase.SAVE_ERROR -> Text(strings.localize(state.error.orEmpty()), color = MaterialTheme.colorScheme.error)
                CodexLoginPhase.SAVING, CodexLoginPhase.COMPLETE -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth()); Text(strings.getString(R.string.ui_saving_authorization))
                }
                CodexLoginPhase.POLLING -> state.challenge?.let { challenge ->
                    Text(strings.getString(R.string.ui_browser_authorization_help))
                    CopyRow(strings.getString(R.string.ui_device_code), challenge.userCode, { copy(context, strings.getString(R.string.ui_device_code), it) })
                    Button(onClick = { try { context.startActivity(Intent(Intent.ACTION_VIEW, challenge.verificationUrl.toUri())) } catch (_: Exception) { loginModel.browserUnavailable() } }, modifier = Modifier.fillMaxWidth()) { Text(strings.getString(R.string.ui_open_sign_in)); Spacer(Modifier.width(8.dp)); Icon(PathPilotIcons.OpenInNew, null) }
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(strings.getString(R.string.ui_waiting_sign_in), style = MaterialTheme.typography.bodySmall)
                }
                else -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(strings.getString(R.string.ui_fetching_device_code)) }
            }
        }
    }, confirmButton = {
        if (state.phase == CodexLoginPhase.SAVE_ERROR) TextButton(onClick = loginModel::retrySave) { Text(strings.getString(R.string.ui_retry_save)) }
        else if (state.phase == CodexLoginPhase.AUTH_ERROR) TextButton(onClick = loginModel::retryAuthorization) { Text(strings.getString(R.string.ui_authorize_again)) }
    }, dismissButton = { TextButton(enabled = !savingTokens, onClick = ::close) { Text(strings.getString(R.string.ui_close)) } })
}
