package dev.pathpilot.app

import android.Manifest
import android.content.res.Resources
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pathpilot.app.runtime.*
import dev.pathpilot.app.ui.icons.PathPilotIcons
import dev.pathpilot.app.ui.i18n.localize
import dev.pathpilot.core.AccessKeys
import dev.pathpilot.core.mcp.*
import dev.pathpilot.core.network.OutboundProxyConfig
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun McpPage(
    store: AppStore,
    selectedId: String?,
    onSelectConnection: (String?) -> Unit,
    catalogClient: (McpConnection, () -> OutboundProxyConfig) -> McpClient = { connection, proxy -> McpClient(connection, proxy) },
) {
    val context = LocalContext.current
    val strings = LocalResources.current
    val config by store.config.collectAsStateWithLifecycle()
    val settings by store.mcp.collectAsStateWithLifecycle()
    val runtime by McpRuntime.state.collectAsStateWithLifecycle()
    val storageError by store.storageError.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var editorId by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var presetPicker by rememberSaveable { mutableStateOf(false) }
    var fetchConnectionId by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var portDialog by remember { mutableStateOf(false) }
    var resetKey by remember { mutableStateOf(false) }
    var notificationPending by rememberSaveable { mutableStateOf(false) }
    val selected = settings.connections.find { it.id == selectedId }
    val active = runtime.running || runtime.starting || runtime.stopping || notificationPending
    val busy = saving || storageError != null

    fun update(transform: (McpSettings) -> McpSettings, onSaved: () -> Unit = {}) {
        if (saving) return
        saving = true
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { store.updateMcpSettings(transform) }
                onSaved()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logMcpFailure("save", failure)
                error = if (failure is IllegalArgumentException || failure is IllegalStateException)
                    failure.message ?: strings.getString(R.string.mcp_error_save)
                else strings.getString(R.string.mcp_error_save)
            } finally { saving = false }
        }
    }
    LaunchedEffect(selectedId, selected?.id) {
        if (selectedId != null && selected == null) onSelectConnection(null)
    }
    BackHandler(enabled = selected != null) { onSelectConnection(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (notificationPending) {
            notificationPending = false
            McpService.start(context)
        }
    }
    fun sharing(enabled: Boolean) {
        if (!enabled) {
            notificationPending = false
            McpService.stop(context)
        } else if (!active) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPending = true
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else McpService.start(context)
        }
    }
    fun copied(value: String, secret: Boolean = false) {
        copyMcpValue(context, value, secret)
        scope.launch { snackbar.showSnackbar(strings.getString(R.string.ui_copied)) }
    }
    fun addPreset(preset: McpPreset, token: String) {
        fun open(connection: McpConnection) {
            presetPicker = false
            fetchConnectionId = connection.id
            onSelectConnection(connection.id)
        }
        val existing = preset.existingConnection(settings.connections, token)
        if (existing != null) open(existing)
        else {
            val connection = preset.connection(token)
            update({ it.copy(connections = it.connections + connection) }) { open(connection) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(selected?.name ?: stringResource(R.string.mcp_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { if (selected != null) IconButton(onClick = { onSelectConnection(null) }) {
                    Icon(PathPilotIcons.Back, stringResource(R.string.ui_back))
                } },
                actions = {
                    if (selected == null) IconButton(enabled = !busy, onClick = { error = null; presetPicker = true }) {
                        Icon(PathPilotIcons.Add, stringResource(R.string.mcp_add_connection))
                    } else IconButton(enabled = !busy, onClick = { editorId = selected.id }) {
                        Icon(PathPilotIcons.Edit, stringResource(R.string.mcp_edit))
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.mcp_more)) }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.mcp_help)) },
                                leadingIcon = { Icon(Icons.Outlined.Info, null) },
                                onClick = { menu = false; help = true })
                            DropdownMenuItem(text = { Column {
                                Text(stringResource(R.string.mcp_listening_port))
                                Text(if (active) stringResource(R.string.mcp_stop_to_change_port) else settings.port.toString(),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } }, enabled = !busy && !active,
                                onClick = { menu = false; portDialog = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.mcp_reset_key)) }, enabled = !busy,
                                onClick = { menu = false; resetKey = true })
                            if (selected != null) DropdownMenuItem(text = { Text(stringResource(R.string.mcp_delete)) },
                                leadingIcon = { Icon(PathPilotIcons.Delete, null) }, enabled = !busy,
                                onClick = { menu = false; deleteId = selected.id })
                        }
                    }
                }, windowInsets = WindowInsets(0, 0, 0, 0))
        },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            (error ?: runtime.error)?.let {
                Text(strings.localize(it), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (selected == null) {
                LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (active || settings.connections.isNotEmpty()) item {
                        McpSharingControl(runtime, notificationPending,
                            enabled = !busy && !runtime.starting && !runtime.stopping &&
                                (runtime.running || settings.connections.any { it.enabled }), onChange = ::sharing)
                    }
                    if (settings.connections.isEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(stringResource(R.string.mcp_no_connections), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FilledTonalButton(enabled = !busy, onClick = { error = null; presetPicker = true }) {
                                Icon(PathPilotIcons.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.mcp_add_connection))
                            }
                        }
                    } else {
                        item { Text(stringResource(R.string.mcp_connections), style = MaterialTheme.typography.titleMedium) }
                        items(settings.connections, key = { it.id }) { connection ->
                            McpConnectionCard(connection, busy, onOpen = { onSelectConnection(connection.id) }, onEnabled = { enabled ->
                                update({ state -> state.copy(connections = state.connections.map {
                                    if (it.id == connection.id) it.copy(enabled = enabled) else it
                                }) })
                            })
                        }
                    }
                    if (runtime.recentCalls.isNotEmpty()) {
                        item { Text(stringResource(R.string.mcp_recent_calls), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium) }
                        items(runtime.recentCalls.take(5), key = { it.id }) { event -> McpCallRow(event) }
                    }
                }
            } else {
                McpConnectionDetails(selected, settings, store, busy, runtime,
                    catalogClient = catalogClient,
                    fetchOnOpen = fetchConnectionId == selected.id,
                    onFetchStarted = { fetchConnectionId = null },
                    notificationPending = notificationPending,
                    sharingEnabled = !busy && !runtime.starting && !runtime.stopping &&
                        (runtime.running || settings.connections.any { it.enabled }),
                    onSharing = ::sharing,
                    onCopy = ::copied,
                    onAllowed = { allowed -> update({ state -> state.copy(connections = state.connections.map {
                        if (it.id == selected.id) it.copy(allowedTools = allowed) else it
                    }) }) },
                )
            }
        }
    }
    if (presetPicker) McpPresetPicker(settings.connections, saving, error?.let(strings::localize),
        onDismiss = { if (!saving) presetPicker = false },
        onCustom = { presetPicker = false; error = null; adding = true },
        onAdd = ::addPreset,
    )
    val existing = settings.connections.find { it.id == editorId }
    if (adding || existing != null) McpConnectionEditor(existing, saving, error?.let(strings::localize), onDismiss = { if (!saving) { adding = false; editorId = null } },
        onSave = { connection -> update({ state -> state.copy(connections = state.connections.filterNot { it.id == connection.id } + connection) }) {
            adding = false; editorId = null; onSelectConnection(connection.id)
        } })
    if (portDialog) McpPortDialog(settings.port, config.port, saving, error?.let(strings::localize), onDismiss = { if (!saving) portDialog = false }) { port ->
        update({ it.copy(port = port) }) { portDialog = false }
    }
    settings.connections.find { it.id == deleteId }?.let { connection ->
        AlertDialog(onDismissRequest = { if (!saving) deleteId = null }, title = { Text(stringResource(R.string.mcp_delete_title)) },
            text = { Text(stringResource(R.string.mcp_delete_body, connection.name)) },
            confirmButton = { TextButton(enabled = !saving, onClick = {
                update({ it.copy(connections = it.connections.filterNot { item -> item.id == connection.id }) }) {
                    if (selectedId == connection.id) onSelectConnection(null)
                    deleteId = null
                }
            }) { Text(stringResource(R.string.mcp_delete)) } },
            dismissButton = { TextButton(enabled = !saving, onClick = { deleteId = null }) { Text(stringResource(R.string.mcp_cancel)) } })
    }
    if (resetKey) AlertDialog(onDismissRequest = { if (!saving) resetKey = false },
        title = { Text(stringResource(R.string.mcp_reset_key_title)) }, text = { Text(stringResource(R.string.mcp_reset_key_body)) },
        confirmButton = { TextButton(enabled = !saving, onClick = { update({ it.copy(accessKey = AccessKeys.generate()) }) { resetKey = false } }) {
            Text(stringResource(R.string.mcp_reset))
        } }, dismissButton = { TextButton(enabled = !saving, onClick = { resetKey = false }) { Text(stringResource(R.string.mcp_cancel)) } })
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text(stringResource(R.string.mcp_help)) },
        text = { Text(stringResource(R.string.mcp_help_body), Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = { help = false }) { Text(stringResource(R.string.mcp_close)) } })
}

@Composable
private fun McpSharingControl(state: McpRuntimeState, pending: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    SharingControlCard(
        title = stringResource(R.string.mcp_share_title),
        status = stringResource(when {
            state.stopping -> R.string.ui_stopping
            state.starting -> R.string.ui_starting
            pending -> R.string.ui_waiting_notification
            state.running -> R.string.ui_on
            else -> R.string.ui_off
        }),
        checked = state.running || state.starting || pending,
        enabled = enabled,
        running = state.running,
        onCheckedChange = onChange,
    )
}

@Composable
private fun McpConnectionCard(connection: McpConnection, busy: Boolean, onOpen: () -> Unit, onEnabled: (Boolean) -> Unit) {
    OutlinedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(PathPilotIcons.Link, null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(connection.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(connection.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (connection.allowedTools == null) stringResource(R.string.mcp_all_tools)
                    else stringResource(R.string.mcp_allowed_count, (connection.allowedTools?.size ?: 0)), style = MaterialTheme.typography.bodySmall)
            }
            val label = stringResource(R.string.mcp_connection_enabled, connection.name)
            Switch(connection.enabled, onCheckedChange = onEnabled, enabled = !busy, modifier = Modifier.semantics { contentDescription = label })
        }
    }
}

@Composable
private fun McpConnectionDetails(
    connection: McpConnection, settings: McpSettings, store: AppStore, busy: Boolean, runtime: McpRuntimeState,
    catalogClient: (McpConnection, () -> OutboundProxyConfig) -> McpClient,
    fetchOnOpen: Boolean, onFetchStarted: () -> Unit,
    notificationPending: Boolean, sharingEnabled: Boolean, onSharing: (Boolean) -> Unit,
    onCopy: (String, Boolean) -> Unit, onAllowed: (Set<String>?) -> Unit,
) {
    val strings = LocalResources.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = if (fetchOnOpen) {
        if (connection.enabled) 2 else 3
    } else 0)
    var tools by remember(connection.id, connection.url, connection.bearerToken) { mutableStateOf<List<McpTool>?>(null) }
    var info by remember(connection.id, connection.url, connection.bearerToken) { mutableStateOf<McpServerInfo?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val currentClient = remember { AtomicReference<McpClient?>() }
    DisposableEffect(connection.id, connection.url, connection.bearerToken) {
        onDispose { currentClient.getAndSet(null)?.close(); job?.cancel() }
    }
    fun fetchTools() {
        loading = true; failure = null
        job = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    catalogClient(connection, { store.config.value.outboundProxy }).use { client ->
                        currentClient.set(client)
                        val server = client.connect()
                        server to client.listTools()
                    }
                }
                info = result.first; tools = result.second
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logMcpFailure("get tools", error)
                failure = mcpConnectionError(strings, error)
            } finally { currentClient.set(null); loading = false }
        }
    }
    LaunchedEffect(connection.id, fetchOnOpen) {
        if (fetchOnOpen) {
            onFetchStarted()
            fetchTools()
        }
    }
    LazyColumn(state = listState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { McpSharingControl(runtime, notificationPending, sharingEnabled, onSharing) }
        if (!connection.enabled) item { Text(stringResource(R.string.mcp_disabled), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { McpAccessCard(connection.id, settings.port, settings.accessKey, runtime, onCopy) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.mcp_tools), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(enabled = !loading, onClick = ::fetchTools) {
                        if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(PathPilotIcons.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (loading) R.string.mcp_connecting else R.string.mcp_get_tools))
                    }
                }
                info?.let { Text(stringResource(R.string.mcp_server_info, it.name, it.protocolVersion),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        item {
            val label = stringResource(R.string.mcp_allow_all)
            ListItem(content = { Text(label) }, supportingContent = { Text(stringResource(R.string.mcp_allow_all_help)) },
                trailingContent = { Switch(checked = connection.allowedTools == null, enabled = !busy,
                    onCheckedChange = { onAllowed(if (it) null else emptySet()) },
                    modifier = Modifier.semantics { contentDescription = label }) })
        }
        if (tools?.isEmpty() == true) item { Text(stringResource(R.string.mcp_no_tools), style = MaterialTheme.typography.bodyMedium) }
        else items(tools.orEmpty(), key = { it.name }) { tool ->
            val allowed = connection.allowedTools?.contains(tool.name) ?: true
            OutlinedCard {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(tool.name, style = MaterialTheme.typography.titleMedium)
                        tool.description?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    val label = stringResource(R.string.mcp_share_tool, tool.name)
                    Switch(checked = allowed, enabled = !busy, onCheckedChange = { checked ->
                        val selected = connection.allowedTools ?: tools.orEmpty().map { it.name }.toSet()
                        onAllowed(if (checked) selected + tool.name else selected - tool.name)
                    }, modifier = Modifier.semantics { contentDescription = label })
                }
            }
        }
    }
}

@Composable
private fun McpCallRow(event: McpCallEvent) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(event.toolName, style = MaterialTheme.typography.titleMedium)
            Text(event.connectionName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.mcp_call_summary, stringResource(if (event.success) R.string.mcp_success else R.string.mcp_failed), event.durationMs),
                style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(Instant.ofEpochMilli(event.startedAtEpochMs).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("HH:mm:ss")), style = MaterialTheme.typography.bodySmall)
                event.errorCode?.let { code ->
                    val number = code.substringAfter('_', "").toIntOrNull()
                    val label = when (code) {
                        "tool_disabled" -> stringResource(R.string.mcp_call_tool_disabled)
                        "invalid_params" -> stringResource(R.string.mcp_call_invalid_params)
                        "tool_error" -> stringResource(R.string.mcp_call_tool_error)
                        "connection_failed" -> stringResource(R.string.mcp_call_connection_failed)
                        "protocol_error" -> stringResource(R.string.mcp_call_protocol_error)
                        else -> when {
                            code.startsWith("http_") && number != null -> stringResource(R.string.mcp_error_http, number)
                            code.startsWith("rpc_") && number != null -> stringResource(R.string.mcp_call_rpc_error, number)
                            else -> code
                        }
                    }
                    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun mcpConnectionError(strings: Resources, error: Exception): String = when {
    error is McpException && error.httpStatus != null -> strings.getString(R.string.mcp_error_http, error.httpStatus)
    error is McpException && error.code != null -> strings.getString(R.string.mcp_error_rpc, error.code, strings.localize(error.message.orEmpty()))
    error is UnknownHostException -> strings.getString(R.string.mcp_error_host)
    error is SocketTimeoutException -> strings.getString(R.string.mcp_error_timeout)
    error is SSLException -> strings.getString(R.string.mcp_error_tls)
    error is McpException || error is IllegalArgumentException -> error.message?.let(strings::localize) ?: strings.getString(R.string.mcp_error_connection)
    error is IOException -> strings.getString(R.string.mcp_error_connection)
    else -> strings.getString(R.string.mcp_error_fetch)
}

private fun logMcpFailure(operation: String, error: Exception) {
    Log.e("PathPilotMcp", "$operation: ${error.javaClass.simpleName}\n${error.stackTrace.joinToString("\n")}")
}
