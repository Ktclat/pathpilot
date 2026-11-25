package dev.pathpilot.app.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pathpilot.app.runtime.AppStore
import dev.pathpilot.app.runtime.McpRuntime
import dev.pathpilot.app.runtime.RouterRuntime
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class BackupStage { NONE, EXPORT_PASSWORD, IMPORT_PASSWORD, PREVIEW }
data class BackupUiState(
    val stage: BackupStage = BackupStage.NONE,
    val busy: Boolean = false,
    val preview: BackupSnapshot? = null,
    val error: BackupError? = null,
    val fileError: Boolean = false,
    val encryptedStorageError: Boolean = false,
    val configurationError: String? = null,
)
sealed interface BackupEffect {
    data object ChooseExportFile : BackupEffect
    data object Exported : BackupEffect
    data class Restored(val language: AppLanguage) : BackupEffect
}

/** Keeps pending encrypted file data and preview state across Activity recreation. */
class SettingsBackupViewModel(private val store: AppStore) : ViewModel() {
    private val mutable = MutableStateFlow(BackupUiState())
    val state = mutable.asStateFlow()
    private val events = Channel<BackupEffect>(Channel.BUFFERED)
    val effects = events.receiveAsFlow()
    private var exportBytes: ByteArray? = null
    private var importBytes: ByteArray? = null

    fun startExport() { exportBytes = null; mutable.value = BackupUiState(stage = BackupStage.EXPORT_PASSWORD) }
    fun dismiss() {
        if (mutable.value.busy) return
        exportBytes = null
        importBytes = null
        mutable.value = BackupUiState()
    }

    fun prepareExport(password: CharArray, includeSessions: Boolean) = work {
        try { exportBytes = withContext(Dispatchers.IO) { store.exportBackup(password, includeSessions) } }
        finally { password.fill('\u0000') }
        mutable.value = BackupUiState()
        events.send(BackupEffect.ChooseExportFile)
    }

    fun writeExport(resolver: ContentResolver, uri: Uri?) {
        if (uri == null) { exportBytes = null; return }
        work {
            val bytes = exportBytes ?: throw IOException("Export data no longer available")
            withContext(Dispatchers.IO) {
                val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open backup destination")
                output.use { it.write(bytes); it.flush() }
            }
            exportBytes = null
            mutable.value = BackupUiState()
            events.send(BackupEffect.Exported)
        }
    }

    fun readImport(resolver: ContentResolver, uri: Uri?) {
        if (uri == null) return
        importBytes = null
        mutable.value = BackupUiState(stage = BackupStage.IMPORT_PASSWORD)
        work {
            importBytes = withContext(Dispatchers.IO) {
                val input = resolver.openInputStream(uri) ?: throw IOException("Cannot open backup")
                input.use {
                    val result = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8_192)
                    while (true) {
                        val count = it.read(buffer)
                        if (count < 0) break
                        if (result.size() + count > BackupCodec.MAX_FILE_BYTES) throw BackupException(BackupError.FILE_TOO_LARGE)
                        result.write(buffer, 0, count)
                    }
                    result.toByteArray()
                }
            }
        }
    }

    fun decrypt(password: CharArray) = work {
        val snapshot = try {
            withContext(Dispatchers.IO) {
                BackupCodec.decode(importBytes ?: throw IOException("Select a backup file"), password)
            }
        } finally { password.fill('\u0000') }
        mutable.value = BackupUiState(stage = BackupStage.PREVIEW, preview = snapshot)
    }

    fun restore(restoreConnectionSettings: Boolean) = work {
        val snapshot = requireNotNull(mutable.value.preview)
        val router = RouterRuntime.state.value
        val mcp = McpRuntime.state.value
        check(!router.running && !router.starting && !router.stopping && !mcp.running && !mcp.starting && !mcp.stopping) {
            "请先停止模型与 MCP 共享"
        }
        withContext(Dispatchers.IO) { store.restoreBackup(snapshot, restoreConnectionSettings) }
        importBytes = null
        mutable.value = BackupUiState()
        events.send(BackupEffect.Restored(snapshot.preferences.language))
    }

    private fun work(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = null, fileError = false, encryptedStorageError = false, configurationError = null)
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: BackupException) { mutable.value = mutable.value.copy(error = error.reason) }
            catch (_: IOException) { mutable.value = mutable.value.copy(fileError = true) }
            catch (_: java.security.GeneralSecurityException) { mutable.value = mutable.value.copy(encryptedStorageError = true) }
            catch (_: SecurityException) { mutable.value = mutable.value.copy(fileError = true) }
            catch (error: IllegalArgumentException) { mutable.value = mutable.value.copy(configurationError = error.message) }
            catch (error: IllegalStateException) { mutable.value = mutable.value.copy(configurationError = error.message) }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
}
