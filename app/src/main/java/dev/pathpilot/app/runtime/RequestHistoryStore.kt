package dev.pathpilot.app.runtime

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import androidx.annotation.WorkerThread
import dev.pathpilot.core.RequestEvent
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class RequestHistoryState(
    val events: List<RequestEvent> = emptyList(),
    val error: String? = null,
)

/** Request metadata only. The owner constructs and calls this store from an IO thread. */
class RequestHistoryStore @WorkerThread internal constructor(context: Context, private val file: File) {
    @WorkerThread constructor(context: Context) : this(context, File(context.noBackupFilesDir, "request-history.db"))

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutableState = MutableStateFlow(RequestHistoryState())
    val state: StateFlow<RequestHistoryState> = mutableState.asStateFlow()
    // Failed writes can be retried without changing the model request's result.
    private val pending = linkedMapOf<String, RequestEvent>()
    private val database = object : SQLiteOpenHelper(
        context.applicationContext, file.absolutePath, null, 1,
        DatabaseErrorHandler { db ->
            // Android's default handler deletes corrupt databases. Keep the file and expose
            // the read/write failure instead of silently replacing history with an empty list.
            Log.e(TAG, "Request history database is corrupt")
            db.close()
        },
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE requests (id TEXT PRIMARY KEY NOT NULL, started_at INTEGER NOT NULL, event_json TEXT NOT NULL)")
            db.execSQL("CREATE INDEX requests_started_at ON requests(started_at DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("Unsupported request history schema version: $oldVersion -> $newVersion")
        }
    }

    init { reload() }

    @WorkerThread
    @Synchronized
    fun record(event: RequestEvent) {
        pending.putIfAbsent(event.id, event)
        if (pending.size > MAX_RECORDS) pending.remove(pending.minBy { it.value.startedAtEpochMs }.key)
        attempt("请求记录未保存，请重试") { commitPending() }
    }

    @WorkerThread
    @Synchronized
    fun reload() {
        attempt(if (pending.isEmpty()) "无法读取请求记录，请重试" else "请求记录未保存，请重试") {
            if (pending.isEmpty()) read(database.readableDatabase) else commitPending()
        }
    }

    @WorkerThread
    @Synchronized
    fun clear() {
        attempt("无法清空请求记录，请重试") {
            database.writableDatabase.delete("requests", null, null)
            pending.clear()
            emptyList()
        }
    }

    private fun commitPending(): List<RequestEvent> {
        val db = database.writableDatabase
        val events: List<RequestEvent>
        db.beginTransaction()
        try {
            pending.values.forEach { event ->
                db.insertWithOnConflict("requests", null, ContentValues().apply {
                    put("id", event.id)
                    put("started_at", event.startedAtEpochMs)
                    put("event_json", json.encodeToString(event))
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.execSQL("DELETE FROM requests WHERE rowid NOT IN (SELECT rowid FROM requests ORDER BY started_at DESC, rowid DESC LIMIT $MAX_RECORDS)")
            events = read(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        pending.clear()
        return events
    }

    private fun read(db: SQLiteDatabase): List<RequestEvent> = buildList {
        db.query("requests", arrayOf("event_json"), null, null, null, null,
            "started_at DESC, rowid DESC", MAX_RECORDS.toString()).use { cursor ->
            while (cursor.moveToNext()) add(json.decodeFromString<RequestEvent>(cursor.getString(0)))
        }
    }

    private fun attempt(message: String, operation: () -> List<RequestEvent>) {
        try {
            val directory = requireNotNull(file.parentFile)
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create request history directory")
            mutableState.value = RequestHistoryState(events = operation())
        } catch (error: SQLiteException) {
            reportFailure(message, error)
        } catch (error: SerializationException) {
            reportFailure(message, error)
        } catch (error: IOException) {
            reportFailure(message, error)
        }
    }

    private fun reportFailure(message: String, error: Exception) {
        Log.e(TAG, "Request history operation failed: ${error.javaClass.simpleName}")
        mutableState.value = mutableState.value.copy(error = message)
    }

    @WorkerThread
    @Synchronized
    internal fun close() = database.close()

    companion object {
        const val MAX_RECORDS = 1_000
        private const val TAG = "PathPilotHistory"
    }
}
