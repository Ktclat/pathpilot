package dev.pathpilot.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.pathpilot.app.runtime.*

@Composable
internal fun McpAccessCard(connectionId: String, port: Int, accessKey: String, runtime: McpRuntimeState, onCopy: (String, Boolean) -> Unit) {
    var onThisDevice by rememberSaveable { mutableStateOf(true) }
    val snapshot = rememberSharingAddresses(runtime.running, runtime.stopping, runtime.addresses, runtime.addressWarning)
    val addressPort = if (runtime.running || runtime.stopping) runtime.port else port
    OutlinedCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val addressLabel = stringResource(R.string.mcp_endpoint)
            Text(addressLabel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SharingAddressSelector(snapshot, addressPort, "/mcp/$connectionId", onThisDevice, { onThisDevice = it },
                addressLabel, onCopy = { _, value -> onCopy(value, false) })
            HorizontalDivider()
            Text(stringResource(R.string.ui_access_key), style = MaterialTheme.typography.titleMedium)
            AccessKeyRow(accessKey, onCopy = { onCopy(it, true) })
        }
    }
}

internal fun copyMcpValue(context: Context, value: String, secret: Boolean) {
    val clip = ClipData.newPlainText(context.getString(if (secret) R.string.ui_access_key else R.string.mcp_endpoint), value)
    if (secret && Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply {
        putBoolean("android.content.extra.IS_SENSITIVE", true)
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}
