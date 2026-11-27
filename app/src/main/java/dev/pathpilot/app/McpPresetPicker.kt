package dev.pathpilot.app

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.pathpilot.app.ui.icons.PathPilotIcons
import dev.pathpilot.core.mcp.McpConnection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun McpPresetPicker(
    connections: List<McpConnection>,
    saving: Boolean,
    saveError: String?,
    onDismiss: () -> Unit,
    onCustom: () -> Unit,
    onAdd: (McpPreset, String) -> Unit,
) {
    var credentialPresetId by rememberSaveable { mutableStateOf<String?>(null) }
    val preset = McpPresets.all.find { it.id == credentialPresetId }
    fun back() { if (!saving) credentialPresetId = null }
    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }, sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )) {
        BackHandler(enabled = preset != null) { back() }
        if (preset == null) {
            Column(Modifier.fillMaxWidth().testTag("mcp-preset-list").verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.mcp_add_connection), style = MaterialTheme.typography.headlineSmall)
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                McpPresets.all.forEach { entry ->
                    val existing = entry.existingConnection(connections)
                    OutlinedCard(onClick = {
                        if (entry.requiresToken) credentialPresetId = entry.id else onAdd(entry, "")
                    }, enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("mcp-preset-${entry.id}")) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (entry.requiresToken) PathPilotIcons.Key else PathPilotIcons.Link, null)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(entry.name, style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(entry.description), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (entry.requiresToken) Text(stringResource(requireNotNull(entry.tokenLabel)),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            Icon(if (existing != null) PathPilotIcons.Check else PathPilotIcons.Add,
                                stringResource(if (existing != null) R.string.mcp_preset_added else R.string.mcp_preset_add))
                        }
                    }
                }
                TextButton(enabled = !saving, onClick = onCustom, modifier = Modifier.fillMaxWidth().testTag("mcp-preset-custom")) {
                    Icon(PathPilotIcons.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.mcp_preset_custom))
                }
            }
        } else {
            McpPresetCredentials(preset, saving, saveError, onBack = ::back, onAdd = { onAdd(preset, it) })
        }
    }
}

@Composable
private fun McpPresetCredentials(preset: McpPreset, saving: Boolean, saveError: String?, onBack: () -> Unit, onAdd: (String) -> Unit) {
    val context = LocalContext.current
    var token by remember(preset.id) { mutableStateOf("") }
    var showToken by remember(preset.id) { mutableStateOf(false) }
    var submitted by remember(preset.id) { mutableStateOf(false) }
    var browserUnavailable by remember(preset.id) { mutableStateOf(false) }
    val validToken = token.trim().isNotEmpty() && token.trim().all { it > ' ' && it <= '~' }
    Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = !saving, onClick = onBack) {
                Icon(PathPilotIcons.Back, stringResource(R.string.mcp_preset_back_to_catalog))
            }
            Text(preset.name, style = MaterialTheme.typography.headlineSmall)
        }
        Text(preset.url, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth().testTag("mcp-preset-token"),
            enabled = !saving, singleLine = true, label = { Text(stringResource(requireNotNull(preset.tokenLabel))) },
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
            trailingIcon = { IconButton(onClick = { showToken = !showToken }) {
                Icon(if (showToken) PathPilotIcons.VisibilityOff else PathPilotIcons.Visibility,
                    stringResource(if (showToken) R.string.ui_hide_key else R.string.ui_show_key))
            } }, isError = submitted && !validToken,
            supportingText = if (submitted && !validToken) { { Text(stringResource(
                if (token.isBlank()) R.string.mcp_token_required else R.string.mcp_token_invalid)) } } else null,
        )
        preset.tokenUrl?.let { link ->
            TextButton(enabled = !saving, onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, link.toUri()))
                    browserUnavailable = false
                } catch (_: ActivityNotFoundException) { browserUnavailable = true }
            }) {
                Text(stringResource(R.string.mcp_preset_get_token)); Spacer(Modifier.width(8.dp))
                Icon(PathPilotIcons.OpenInNew, null, Modifier.size(18.dp))
            }
        }
        if (browserUnavailable) Text(stringResource(R.string.mcp_preset_browser_unavailable),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        Button(enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("mcp-preset-add"), onClick = {
            submitted = true
            if (validToken) onAdd(token.trim())
        }) {
            if (saving) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
            Text(stringResource(R.string.mcp_preset_add))
        }
    }
}
