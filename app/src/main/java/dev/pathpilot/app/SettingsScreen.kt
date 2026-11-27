package dev.pathpilot.app

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pathpilot.app.runtime.AppStore
import dev.pathpilot.app.runtime.AppLanguageController
import dev.pathpilot.app.runtime.McpRuntime
import dev.pathpilot.app.runtime.RouterRuntime
import dev.pathpilot.app.settings.*
import dev.pathpilot.app.ui.icons.PathPilotIcons
import dev.pathpilot.app.ui.i18n.localize
import dev.pathpilot.app.ui.theme.ThemeOptionsSheet
import dev.pathpilot.app.ui.theme.themeSummary
import dev.pathpilot.core.AccessKeys
import dev.pathpilot.core.network.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsScreen(store: AppStore, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val strings = LocalResources.current
    val config by store.config.collectAsStateWithLifecycle()
    val preferences by store.preferences.collectAsStateWithLifecycle()
    val router by RouterRuntime.state.collectAsStateWithLifecycle()
    val mcpRuntime by McpRuntime.state.collectAsStateWithLifecycle()
    val storageError by store.storageError.collectAsStateWithLifecycle()
    val backup: SettingsBackupViewModel = viewModel { SettingsBackupViewModel(store) }
    val backupState by backup.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val modelActive = router.running || router.starting || router.stopping
    val sharingActive = modelActive || mcpRuntime.running || mcpRuntime.starting || mcpRuntime.stopping
    val editable = !busy && !backupState.busy && storageError == null
    fun open(intent: Intent) {
        try { context.startActivity(intent) }
        catch (_: android.content.ActivityNotFoundException) { onMessage(strings.getString(R.string.settings_no_handler)) }
    }
    fun save(action: () -> Unit, after: () -> Unit = { dialog = null }) {
        if (busy) return
        busy = true
        scope.launch {
            try { withContext(Dispatchers.IO) { action() }; after() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                android.util.Log.e("PathPilotSettings", "Save failed: ${failure.javaClass.simpleName}")
                error = if (failure is IllegalArgumentException || failure is IllegalStateException)
                    failure.message ?: strings.getString(R.string.settings_save_failed)
                else strings.getString(R.string.settings_save_failed)
            } finally { busy = false }
        }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        backup.writeExport(context.contentResolver, it)
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        backup.readImport(context.contentResolver, it)
    }
    LaunchedEffect(backup, context) {
        backup.effects.collect { effect ->
            when (effect) {
                BackupEffect.ChooseExportFile -> exportFile.launch("PathPilot-${java.time.LocalDate.now()}.ppbackup")
                BackupEffect.Exported -> onMessage(strings.getString(R.string.settings_exported))
                is BackupEffect.Restored -> {
                    onMessage(strings.getString(R.string.settings_restored))
                    (context as? Activity)?.let { AppLanguageController.apply(it, effect.language) }
                }
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            SettingsSection(stringResource(R.string.settings_preferences)) {
                SettingsRow(stringResource(R.string.settings_appearance), themeSummary(preferences), editable) { dialog = "theme" }
                HorizontalDivider()
                SettingsRow(stringResource(R.string.settings_language), languageLabel(preferences.language), editable) { dialog = "language" }
            }
        }
        item {
            SettingsSection(stringResource(R.string.settings_connections)) {
                SettingsRow(stringResource(R.string.settings_model_port),
                    if (modelActive) stringResource(R.string.settings_stop_to_edit, config.port) else config.port.toString(),
                    enabled = editable && !modelActive) { dialog = "port" }
                HorizontalDivider()
                SettingsRow(stringResource(R.string.settings_proxy), proxyLabel(config.outboundProxy), editable) { dialog = "proxy" }
                HorizontalDivider()
                SettingsRow(stringResource(R.string.settings_rotate_key), enabled = editable) { dialog = "rotate" }
            }
        }
        item {
            SettingsSection(stringResource(R.string.settings_data)) {
                SettingsRow(stringResource(R.string.settings_export), enabled = editable) { backup.startExport() }
                HorizontalDivider()
                SettingsRow(stringResource(R.string.settings_import),
                    if (sharingActive) stringResource(R.string.settings_stop_before_restore) else null,
                    enabled = !busy && !backupState.busy && !sharingActive) { importFile.launch(arrayOf("*/*")) }
            }
        }
        item {
            SettingsSection(stringResource(R.string.settings_application)) {
                SettingsRow(stringResource(R.string.settings_background)) { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                HorizontalDivider()
                SettingsRow(stringResource(R.string.settings_help)) { open(Intent(Intent.ACTION_VIEW, "https://github.com/Ktclat/pathpilot#readme".toUri())) }
                HorizontalDivider()
                SettingsRow(stringResource(R.string.settings_about), BuildConfig.VERSION_NAME) { dialog = "about" }
            }
        }
    }
    when (dialog) {
        "theme" -> ThemeOptionsSheet(preferences, editable, onDismiss = { dialog = null }) { updated ->
            save({ store.setPreferences(updated) }, after = {})
        }
        "language" -> ChoiceDialog(stringResource(R.string.settings_language), AppLanguage.entries.map { it to languageLabel(it) }, preferences.language,
            enabled = editable, onDismiss = { dialog = null }) { language ->
            save({ store.setPreferences(preferences.copy(language = language)) }) {
                dialog = null
                (context as? Activity)?.let { AppLanguageController.apply(it, language) }
            }
        }
        "port" -> PortDialog(config.port, !editable, { dialog = null }) { port -> save({ store.updateConfig { it.copy(port = port) } }) }
        "proxy" -> ProxyDialog(config.outboundProxy, !editable, { dialog = null }) { proxy -> save({ store.updateConfig { it.copy(outboundProxy = proxy) } }) }
        "rotate" -> AlertDialog(onDismissRequest = { if (editable) dialog = null }, title = { Text(stringResource(R.string.settings_rotate_title)) },
            text = { Text(stringResource(R.string.settings_rotate_body)) },
            confirmButton = { TextButton(enabled = editable, onClick = { save({ store.updateConfig { it.copy(accessKey = AccessKeys.generate()) } }) {
                dialog = null; onMessage(strings.getString(R.string.settings_key_updated))
            } }) { Text(stringResource(R.string.settings_reset)) } },
            dismissButton = { TextButton(enabled = editable, onClick = { dialog = null }) { Text(stringResource(R.string.settings_cancel)) } })
        "about" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("PathPilot") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
                TextButton(onClick = { open(Intent(Intent.ACTION_VIEW, "https://github.com/Ktclat/pathpilot".toUri())) }) { Text(stringResource(R.string.settings_source)) }
                TextButton(onClick = { open(Intent(Intent.ACTION_VIEW, "https://github.com/Ktclat/pathpilot/blob/main/LICENSE".toUri())) }) { Text(stringResource(R.string.settings_license)) }
            }
        }, confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.settings_close)) } })
    }
    BackupDialogs(backupState, backup, sharingActive, storageError != null)
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text(stringResource(R.string.settings_error_title)) },
        text = { Text(strings.localize(message)) }, confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(R.string.settings_close)) } }) }
}

@Composable private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        OutlinedCard(Modifier.fillMaxWidth()) { Column(content = content) }
    }
}

@Composable private fun SettingsRow(title: String, summary: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(modifier = Modifier.clickable(enabled = enabled, onClick = onClick), content = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) }, enabled = enabled)
}

@Composable private fun languageLabel(language: AppLanguage): String = stringResource(when (language) {
    AppLanguage.SYSTEM -> R.string.settings_language_system; AppLanguage.ZH_CN -> R.string.settings_language_zh; AppLanguage.EN -> R.string.settings_language_en
})

@Composable private fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T, enabled: Boolean,
    onDismiss: () -> Unit, onSelect: (T) -> Unit) {
    AlertDialog(onDismissRequest = { if (enabled) onDismiss() }, title = { Text(title) }, text = {
        Column { options.forEach { (value, label) ->
            Row(Modifier.fillMaxWidth().selectable(selected == value, enabled = enabled, role = Role.RadioButton,
                onClick = { onSelect(value) }).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected == value, onClick = null, enabled = enabled)
                Text(label, Modifier.padding(start = 12.dp))
            }
        } }
    }, confirmButton = { TextButton(enabled = enabled, onClick = onDismiss) { Text(stringResource(R.string.settings_close)) } })
}

@Composable private fun PortDialog(current: Int, saving: Boolean, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var input by rememberSaveable { mutableStateOf(current.toString()) }
    val port = input.toIntOrNull()
    val valid = port != null && port in 1024..65535
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text(stringResource(R.string.settings_model_port)) }, text = {
        OutlinedTextField(input, { input = it }, enabled = !saving, label = { Text(stringResource(R.string.settings_port)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = !valid,
            supportingText = if (!valid) { { Text(stringResource(R.string.settings_port_hint)) } } else null)
    }, confirmButton = { TextButton(enabled = !saving && valid, onClick = { onSave(requireNotNull(port)) }) { Text(stringResource(R.string.settings_save)) } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } })
}

@Composable private fun proxyLabel(config: OutboundProxyConfig): String = when (config.mode) {
    OutboundProxyMode.SYSTEM -> stringResource(R.string.settings_proxy_system)
    OutboundProxyMode.HTTP -> "HTTP · ${config.host}:${config.port}"
    OutboundProxyMode.SOCKS5 -> "SOCKS5 · ${config.host}:${config.port}"
}

@Composable private fun ProxyDialog(current: OutboundProxyConfig, saving: Boolean, onDismiss: () -> Unit, onSave: (OutboundProxyConfig) -> Unit) {
    val strings = LocalResources.current
    var mode by rememberSaveable { mutableStateOf(current.mode) }
    var host by rememberSaveable { mutableStateOf(current.host.ifBlank { "127.0.0.1" }) }
    var port by rememberSaveable { mutableStateOf(current.port.toString()) }
    var submitted by remember { mutableStateOf(false) }
    val draft = OutboundProxyConfig(mode, host, port.toIntOrNull() ?: 0)
    val validation = OutboundProxyValidation.validate(draft)
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text(stringResource(R.string.settings_proxy)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutboundProxyMode.entries.forEach { option ->
                Row(Modifier.fillMaxWidth().selectable(mode == option, enabled = !saving, role = Role.RadioButton, onClick = { mode = option }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(mode == option, onClick = null, enabled = !saving)
                    Text(if (option == OutboundProxyMode.SYSTEM) stringResource(R.string.settings_proxy_system) else option.name, Modifier.padding(start = 12.dp))
                }
            }
            if (mode == OutboundProxyMode.SYSTEM) Text(stringResource(R.string.settings_proxy_vpn), style = MaterialTheme.typography.bodySmall)
            else {
                OutlinedTextField(host, { host = it }, enabled = !saving, singleLine = true, label = { Text(stringResource(R.string.settings_host)) },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                    isError = submitted && validation.hostError != null,
                    supportingText = if (submitted && validation.hostError != null) { { Text(strings.localize(validation.hostError.orEmpty())) } } else null)
                OutlinedTextField(port, { port = it }, enabled = !saving, singleLine = true, label = { Text(stringResource(R.string.settings_port)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = submitted && validation.portError != null,
                    supportingText = if (submitted && validation.portError != null) { { Text(strings.localize(validation.portError.orEmpty())) } } else null)
                Text(stringResource(R.string.settings_browser_network), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }, confirmButton = { TextButton(enabled = !saving, onClick = { submitted = true; if (validation.isValid) onSave(OutboundProxyValidation.normalized(draft)) }) { Text(stringResource(R.string.settings_save)) } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } })
}

@Composable private fun BackupDialogs(state: BackupUiState, model: SettingsBackupViewModel, sharingActive: Boolean, damagedStore: Boolean) {
    val strings = LocalResources.current
    var password by remember(state.stage) { mutableStateOf("") }
    var confirmation by remember(state.stage) { mutableStateOf("") }
    var includeSessions by rememberSaveable { mutableStateOf(false) }
    var restoreConnections by remember(state.preview) { mutableStateOf(damagedStore) }
    val problem = when {
        state.error != null -> stringResource(when (state.error) {
            BackupError.PASSWORD_REQUIRED -> R.string.settings_backup_password_required
            BackupError.FILE_TOO_LARGE -> R.string.settings_backup_too_large
            BackupError.INVALID_FORMAT -> R.string.settings_backup_invalid
            BackupError.UNSUPPORTED_VERSION -> R.string.settings_backup_version
            BackupError.WRONG_PASSWORD_OR_DAMAGED_FILE -> R.string.settings_backup_password_error
        })
        state.encryptedStorageError -> stringResource(R.string.settings_backup_storage_error)
        state.fileError -> stringResource(R.string.settings_backup_file_error)
        state.configurationError != null -> strings.localize(state.configurationError)
        else -> null
    }
    if (state.stage == BackupStage.EXPORT_PASSWORD || state.stage == BackupStage.IMPORT_PASSWORD) {
        val exporting = state.stage == BackupStage.EXPORT_PASSWORD
        AlertDialog(onDismissRequest = model::dismiss,
            title = { Text(stringResource(if (exporting) R.string.settings_export_title else R.string.settings_import_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (exporting) Text(stringResource(R.string.settings_export_contents), style = MaterialTheme.typography.bodyMedium)
                PasswordField(password, { password = it }, stringResource(R.string.settings_password), !state.busy)
                if (exporting) {
                    PasswordField(confirmation, { confirmation = it }, stringResource(R.string.settings_confirm_password), !state.busy)
                    if (confirmation.isNotEmpty() && confirmation != password) Text(stringResource(R.string.settings_password_mismatch), color = MaterialTheme.colorScheme.error)
                    Row(Modifier.fillMaxWidth().toggleable(includeSessions, enabled = !state.busy, role = Role.Checkbox, onValueChange = { includeSessions = it }), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(includeSessions, onCheckedChange = null, enabled = !state.busy)
                        Text(stringResource(R.string.settings_include_sessions), Modifier.padding(start = 8.dp))
                    }
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            } },
            confirmButton = { TextButton(enabled = !state.busy && password.isNotEmpty() && (!exporting || password == confirmation) && !state.fileError,
                onClick = { if (exporting) model.prepareExport(password.toCharArray(), includeSessions) else model.decrypt(password.toCharArray()) }) { Text(stringResource(R.string.settings_next)) } },
            dismissButton = { TextButton(enabled = !state.busy, onClick = model::dismiss) { Text(stringResource(R.string.settings_cancel)) } })
    } else if (state.stage == BackupStage.PREVIEW && state.preview != null) {
        val snapshot = state.preview
        AlertDialog(onDismissRequest = model::dismiss, title = { Text(stringResource(R.string.settings_restore_title)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.settings_backup_created, Instant.ofEpochMilli(snapshot.createdAtEpochMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))))
                Text(stringResource(R.string.settings_backup_counts, snapshot.config.providers.size, snapshot.config.routes.size, snapshot.mcp.connections.size))
                Text(stringResource(R.string.settings_backup_replace))
                if (snapshot.includesCodexSessions) Text(stringResource(R.string.settings_include_sessions), style = MaterialTheme.typography.bodyMedium)
                if (damagedStore) Text(stringResource(R.string.settings_restore_connections_forced))
                else Row(Modifier.fillMaxWidth().toggleable(restoreConnections, enabled = !state.busy, role = Role.Checkbox, onValueChange = { restoreConnections = it }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(restoreConnections, onCheckedChange = null, enabled = !state.busy)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(stringResource(R.string.settings_restore_connections))
                        Text(stringResource(R.string.settings_restore_connections_detail), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (sharingActive) Text(stringResource(R.string.settings_stop_before_restore), color = MaterialTheme.colorScheme.error)
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }, confirmButton = { TextButton(enabled = !state.busy && !sharingActive, onClick = { model.restore(damagedStore || restoreConnections) }) { Text(stringResource(R.string.settings_restore)) } },
            dismissButton = { TextButton(enabled = !state.busy, onClick = model::dismiss) { Text(stringResource(R.string.settings_cancel)) } })
    } else if (problem != null) AlertDialog(onDismissRequest = model::dismiss, title = { Text(stringResource(R.string.settings_backup_failed)) },
        text = { Text(problem) }, confirmButton = { TextButton(onClick = model::dismiss) { Text(stringResource(R.string.settings_close)) } })
}

@Composable private fun PasswordField(value: String, onValue: (String) -> Unit, label: String, enabled: Boolean) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value, onValue, Modifier.fillMaxWidth(), enabled = enabled, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) PathPilotIcons.VisibilityOff else PathPilotIcons.Visibility,
            stringResource(if (visible) R.string.settings_hide_password else R.string.settings_show_password)) } })
}
