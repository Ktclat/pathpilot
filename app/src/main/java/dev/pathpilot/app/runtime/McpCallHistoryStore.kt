package dev.pathpilot.app.runtime

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import androidx.annotation.WorkerThread
import dev.pathpilot.core.mcp.McpCallEvent
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class McpHistoryError { READ, WRITE, CLEAR }

internal data class McpCallHistoryState(
    val events: List<McpCallEvent> = emptyList(),
    val error: McpHistoryError? = null,
)

/** Independent, bounded metadata only. Access is serialized on background threads. */
internal class McpCallHistoryStore @WorkerThread constructor(context: Context, file: File) {
    @WorkerThread constructor(context: Context) : this(context, File(context.noBackupFilesDir, "mcp-call-history.db"))

    private val mutableState = MutableStateFlow(McpCallHistoryState())
    val state = mutableState.asStateFlow()
    private val pending = linkedMapOf<String, McpCallEvent>()
    private val database = object : SQLiteOpenHelper(context.applicationContext, file.absolutePath, null, 1,
        DatabaseErrorHandler { db -> db.close() }) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE calls (
                id TEXT PRIMARY KEY NOT NULL, started_at INTEGER NOT NULL,
                connection_id TEXT NOT NULL, connection_name TEXT NOT NULL, tool_name TEXT NOT NULL,
                success INTEGER NOT NULL, duration INTEGER NOT NULL, error_code TEXT
            )""".trimIndent())
            db.execSQL("CREATE INDEX calls_started_at ON calls(started_at DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("Unsupported MCP call history schema: $oldVersion -> $newVersion")
        }
    }

    init { reload() }

    @WorkerThread @Synchronized
    fun record(event: McpCallEvent) {
        // The tool name comes from a request; a large invalid identifier must not inflate the database.
        pending.putIfAbsent(event.id, event.copy(connectionName = event.connectionName.take(256), toolName = event.toolName.take(256)))
        if (pending.size > MAX_RECORDS) pending.remove(pending.minBy { it.value.startedAtEpochMs }.key)
        reload()
    }

    @WorkerThread @Synchronized
    fun reload() {
        if (pending.isEmpty()) {
            attempt(McpHistoryError.READ) { read(database.readableDatabase) }
            return
        }
        attempt(McpHistoryError.WRITE) {
            val db = database.writableDatabase
            db.beginTransaction()
            val events = try {
                for (event in pending.values) {
                    db.insertWithOnConflict("calls", null, ContentValues().apply {
                        put("id", event.id)
                        put("started_at", event.startedAtEpochMs)
                        put("connection_id", event.connectionId)
                        put("connection_name", event.connectionName)
                        put("tool_name", event.toolName)
                        put("success", if (event.success) 1 else 0)
                        put("duration", event.durationMs)
                        put("error_code", event.errorCode)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                }
                db.execSQL("""DELETE FROM calls WHERE rowid NOT IN
                    (SELECT rowid FROM calls ORDER BY started_at DESC, rowid DESC LIMIT $MAX_RECORDS)""".trimIndent())
                read(db).also { db.setTransactionSuccessful() }
            } finally {
                db.endTransaction()
            }
            pending.clear()
            events
        }
    }

    @WorkerThread @Synchronized
    fun clear() {
        attempt(McpHistoryError.CLEAR) {
            database.writableDatabase.delete("calls", null, null)
            pending.clear()
            emptyList()
        }
    }

    private fun read(db: SQLiteDatabase): List<McpCallEvent> = buildList {
        db.query("calls", arrayOf("id", "started_at", "connection_id", "connection_name", "tool_name",
            "success", "duration", "error_code"), null, null, null, null,
            "started_at DESC, rowid DESC", MAX_RECORDS.toString()).use { cursor ->
            while (cursor.moveToNext()) add(McpCallEvent(
                id = cursor.getString(0), startedAtEpochMs = cursor.getLong(1),
                connectionId = cursor.getString(2), connectionName = cursor.getString(3),
                toolName = cursor.getString(4), success = cursor.getInt(5) != 0,
                durationMs = cursor.getLong(6), errorCode = if (cursor.isNull(7)) null else cursor.getString(7),
            ))
        }
    }

    private fun attempt(error: McpHistoryError, operation: () -> List<McpCallEvent>) {
        try {
            mutableState.value = McpCallHistoryState(events = operation())
        } catch (failure: SQLiteException) {
            Log.e("PathPilotMcpHistory", "MCP history operation failed: ${failure.javaClass.simpleName}")
            mutableState.value = mutableState.value.copy(error = error)
        }
    }

    @WorkerThread @Synchronized
    internal fun close() = database.close()

    companion object { const val MAX_RECORDS = 1_000 }
}
