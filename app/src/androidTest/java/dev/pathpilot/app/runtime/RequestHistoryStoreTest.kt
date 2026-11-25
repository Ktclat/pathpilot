package dev.pathpilot.app.runtime

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathpilot.core.RequestEvent
import dev.pathpilot.core.RequestOutcome
import dev.pathpilot.core.TokenUsage
import java.io.File
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RequestHistoryStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var file: File
    private val stores = mutableListOf<RequestHistoryStore>()

    @Before fun prepare() {
        directory = File(context.noBackupFilesDir, "history-test-${UUID.randomUUID()}")
        check(directory.mkdirs())
        file = File(directory, "requests.db")
    }

    @After fun cleanup() {
        stores.forEach { it.close() }
        directory.deleteRecursively()
    }

    @Test fun reopeningRestoresModelUsageAndInterruptedOutcomeInTimeOrder() {
        val history = open()
        val older = event("older", 100).copy(usage = null)
        val newer = event("newer", 300).copy(
            status = 200, outcome = RequestOutcome.INTERRUPTED, errorCode = "stream_interrupted",
            usage = TokenUsage(inputTokens = 40, outputTokens = 12, totalTokens = 52,
                cachedInputTokens = 8, reasoningOutputTokens = 3),
        )
        history.record(newer)
        history.record(older)
        history.close()

        val reopened = open()
        assertNull(reopened.state.value.error)
        assertEquals(listOf(newer, older), reopened.state.value.events)
        assertTrue(file.parentFile!!.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
    }

    @Test fun duplicateEventDoesNotReplaceOrDoubleCountOriginalRequest() {
        val history = open()
        val original = event("one", 10)
        history.record(original)
        history.record(original.copy(status = 500, outcome = RequestOutcome.HTTP_ERROR, startedAtEpochMs = 20))
        assertEquals(listOf(original), history.state.value.events)
        history.close()
        assertEquals(listOf(original), open().state.value.events)
    }

    @Test fun newRequestPrunesHistoryByStartTimeAndKeepsOnlyLatestThousand() {
        val history = open()
        history.close()
        // Seed an existing full history in one transaction; the public record operation below
        // performs the retention check, including an out-of-order old request.
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.beginTransaction()
            try {
                repeat(RequestHistoryStore.MAX_RECORDS) { offset ->
                    val entry = event("seed-$offset", offset.toLong())
                    db.insertOrThrow("requests", null, ContentValues().apply {
                        put("id", entry.id)
                        put("started_at", entry.startedAtEpochMs)
                        put("event_json", Json.encodeToString(entry))
                    })
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        val latest = event("latest", 5_000)
        val reopened = open()
        reopened.record(latest)
        reopened.record(event("late-old-request", -1))
        assertNull(reopened.state.value.error)
        assertEquals(RequestHistoryStore.MAX_RECORDS, reopened.state.value.events.size)
        assertEquals(latest, reopened.state.value.events.first())
        assertFalse(reopened.state.value.events.any { it.id in setOf("seed-0", "late-old-request") })
        reopened.close()
        assertEquals(RequestHistoryStore.MAX_RECORDS, open().state.value.events.size)
    }

    @Test fun clearingHistoryPersistsAcrossStoreRecreation() {
        val history = open()
        history.record(event("clear-me", 10))
        history.clear()
        assertTrue(history.state.value.events.isEmpty())
        assertNull(history.state.value.error)
        history.close()
        assertTrue(open().state.value.events.isEmpty())
    }

    @Test fun failedWriteReportsErrorAndReloadRetriesThePendingRequest() {
        val blockingFile = File(directory, "blocked").apply { writeText("fixture") }
        val target = File(blockingFile, "requests.db")
        val history = open(target)
        val request = event("retry-me", 10)
        history.record(request)
        assertNotNull(history.state.value.error)
        assertTrue(history.state.value.events.isEmpty())

        check(blockingFile.delete())
        history.reload()
        assertNull(history.state.value.error)
        assertEquals(listOf(request), history.state.value.events)
        history.close()
        assertEquals(listOf(request), open(target).state.value.events)
    }

    @Test fun malformedStoredRowReportsErrorWithoutReplacingKnownHistory() {
        val history = open()
        val request = event("original", 10)
        history.record(request)
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.update("requests", ContentValues().apply { put("event_json", "{invalid fixture") }, null, null)
        }
        history.reload()
        assertNotNull(history.state.value.error)
        assertEquals(listOf(request), history.state.value.events)
        assertTrue(file.exists())

        history.clear()
        assertNull(history.state.value.error)
        assertTrue(history.state.value.events.isEmpty())
    }

    private fun open(target: File = file) = RequestHistoryStore(context, target).also(stores::add)

    private fun event(id: String, timestamp: Long) = RequestEvent(
        model = "daily", providerId = "offline-provider", status = 200, durationMs = 125,
        endpoint = "/v1/responses", id = id, startedAtEpochMs = timestamp,
        providerName = "离线测试", upstreamModel = "fixture-model", streaming = true,
    )
}
