package dev.pathpilot.app.runtime

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathpilot.core.mcp.McpCallEvent
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class McpCallHistoryStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.noBackupFilesDir, "mcp-history-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val path = File(directory, "history.db")
    private val stores = mutableListOf<McpCallHistoryStore>()

    @After fun clean() {
        stores.forEach { it.close() }
        directory.deleteRecursively()
    }

    @Test fun persistedRowsKeepMetadataAndDeduplicateWithoutRewritingAnEvent() {
        val success = event("first", 10)
        val failure = event("second", 20).copy(success = false, errorCode = "rpc_-32602")
        open().apply {
            record(success)
            record(failure)
            record(success.copy(toolName = "replacement"))
        }
        val reopened = open()
        assertEquals(listOf(failure, success), reopened.state.value.events)
        assertNull(reopened.state.value.error)
        reopened.clear()
        assertTrue(open().state.value.events.isEmpty())
    }

    @Test fun failedWritesAreVisibleAndRetriedWithoutDuplicatingOrLosingTheCall() {
        val blocked = File(directory, "blocked").apply { writeText("not a directory") }
        val store = open(File(blocked, "history.db"))
        assertEquals(McpHistoryError.READ, store.state.value.error)
        val call = event("retry", 1)
        store.record(call)
        assertEquals(McpHistoryError.WRITE, store.state.value.error)
        assertTrue(store.state.value.events.isEmpty())
        assertTrue(blocked.delete())
        assertTrue(blocked.mkdir())
        store.reload()
        store.reload()
        assertNull(store.state.value.error)
        assertEquals(listOf(call), store.state.value.events)
    }

    @Test fun newestThousandAreKeptByStartTimeIncludingLateCompletions() {
        open().close()
        SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.beginTransaction()
            try {
                repeat(McpCallHistoryStore.MAX_RECORDS) { index ->
                    db.insertOrThrow("calls", null, ContentValues().apply {
                        put("id", "seed-$index")
                        put("started_at", index.toLong())
                        put("connection_id", "id")
                        put("connection_name", "Test connection")
                        put("tool_name", "tool")
                        put("success", 1)
                        put("duration", 4L)
                    })
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        val newest = event("newest", 5_000)
        open().apply {
            record(newest)
            record(event("late-completion", -1))
            close()
        }
        val retained = open().state.value.events
        assertEquals(1_000, retained.size)
        assertEquals(newest, retained.first())
        assertEquals("seed-1", retained.last().id)
    }

    private fun open(file: File = path) = McpCallHistoryStore(context, file).also(stores::add)
    private fun event(id: String, at: Long) = McpCallEvent("id", "Test connection", "tool", true, 4, startedAtEpochMs = at, id = id)
}
