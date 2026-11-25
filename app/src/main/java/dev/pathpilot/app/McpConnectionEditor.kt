package dev.pathpilot.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.pathpilot.app.ui.icons.PathPilotIcons
import dev.pathpilot.core.mcp.McpConnection
import dev.pathpilot.core.mcp.McpValidation
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun McpConnectionEditor(existing: McpConnection?, saving: Boolean, saveError: String?, onDismiss: () -> Unit, onSave: (McpConnection) -> Unit) {
    val id = remember(existing?.id) { existing?.id ?: UUID.randomUUID().toString() }
    var name by remember(id) { mutableStateOf(existing?.name.orEmpty()) }
    var url by remember(id) { mutableStateOf(existing?.url.orEmpty()) }
    var token by remember(id) { mutableStateOf(existing?.bearerToken.orEmpty()) }
    var bearer by remember(id) { mutableStateOf(existing?.bearerToken?.isNotBlank() == true) }
    var showToken by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    LaunchedEffect(saveError) { if (saveError != null) scroll.animateScrollTo(0) }
    val parsed = url.trim().toHttpUrlOrNull()
    val validUrl = parsed != null && parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.fragment == null
    val validToken = !bearer || (token.trim().isNotEmpty() && token.trim().all { it > ' ' && it <= '~' })
    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }, sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(scroll).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(if (existing == null) R.string.mcp_add_connection else R.string.mcp_edit_connection),
                style = MaterialTheme.typography.headlineSmall)
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), enabled = !saving, singleLine = true,
                label = { Text(stringResource(R.string.mcp_name)) }, isError = submitted && name.isBlank(),
                supportingText = if (submitted && name.isBlank()) { { Text(stringResource(R.string.mcp_name_required)) } } else null)
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), enabled = !saving, singleLine = true,
                label = { Text(stringResource(R.string.mcp_url)) }, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                isError = submitted && !validUrl,
                supportingText = if (submitted && !validUrl) { { Text(stringResource(R.string.mcp_url_invalid)) } } else null)
            Text(stringResource(R.string.mcp_authentication), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(false, true).forEach { option ->
                    Row(Modifier.weight(1f).selectable(selected = bearer == option, enabled = !saving, role = Role.RadioButton,
                        onClick = { bearer = option }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = bearer == option, onClick = null, enabled = !saving)
                        Text(stringResource(if (option) R.string.mcp_auth_bearer else R.string.mcp_auth_none))
                    }
                }
            }
            if (bearer) OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), enabled = !saving, singleLine = true,
                label = { Text(stringResource(R.string.mcp_token)) }, visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                trailingIcon = { IconButton(onClick = { showToken = !showToken }) {
                    Icon(if (showToken) PathPilotIcons.VisibilityOff else PathPilotIcons.Visibility,
                        stringResource(if (showToken) R.string.mcp_hide_key else R.string.mcp_show_key))
                } }, isError = submitted && !validToken,
                supportingText = if (submitted && !validToken) { { Text(stringResource(if (token.isBlank()) R.string.mcp_token_required else R.string.mcp_token_invalid)) } } else null)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.mcp_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(enabled = !saving, onClick = {
                    submitted = true
                    if (name.isNotBlank() && validUrl && validToken) onSave(McpValidation.normalized(McpConnection(
                        id = id, name = name, url = url, bearerToken = if (bearer) token else "",
                        enabled = existing?.enabled ?: true, allowedTools = existing?.allowedTools,
                    )))
                }) { Text(stringResource(R.string.mcp_save)) }
            }
        }
    }
}

@Composable
internal fun McpPortDialog(current: Int, modelPort: Int, saving: Boolean, saveError: String?, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var input by remember { mutableStateOf(current.toString()) }
    val port = input.toIntOrNull()
    val error = when {
        port == null || port !in 1024..65535 -> R.string.mcp_port_invalid
        port == modelPort -> R.string.mcp_port_conflict
        else -> null
    }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text(stringResource(R.string.mcp_listening_port)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(input, { input = it }, enabled = !saving, singleLine = true, label = { Text(stringResource(R.string.mcp_port)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = error != null,
                supportingText = error?.let { id -> { Text(stringResource(id)) } })
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        }
    }, confirmButton = { TextButton(enabled = !saving && error == null, onClick = { onSave(requireNotNull(port)) }) {
        Text(stringResource(R.string.mcp_save))
    } }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.mcp_cancel)) } })
}
