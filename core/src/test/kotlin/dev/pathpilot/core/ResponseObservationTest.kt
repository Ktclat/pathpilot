package dev.pathpilot.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ResponseObservationTest {
    /** Exercise both single-byte and chunked reads; observing must never alter delivery. */
    private fun consume(payload: String, sse: Boolean = false, chunkSize: Int = 13): ResponseObservation {
        val expected = payload.toByteArray(Charsets.UTF_8)
        val delivered = ByteArrayOutputStream()
        val observation = ResponseObservation(sse)
        observation.wrap(ByteArrayInputStream(expected)).use { source ->
            val first = source.read()
            if (first >= 0) delivered.write(first)
            val bytes = ByteArray(chunkSize)
            while (true) {
                val count = source.read(bytes)
                if (count < 0) break
                delivered.write(bytes, 0, count)
            }
        }
        assertArrayEquals("Observation must preserve every response byte", expected, delivered.toByteArray())
        return observation
    }

    @Test fun partialCountsRemainUnknownAndOnlyCompleteCountsCanSupplyTotal() {
        val cases = listOf(
            """{"usage":{"output_tokens":4}}""" to TokenUsage(outputTokens = 4),
            """{"usage":{"input_tokens":0,"output_tokens":0}}""" to TokenUsage(0, 0, 0),
            """{"usage":{"input_tokens":10,"output_tokens":5}}""" to TokenUsage(10, 5, 15),
            """{"usage":{"input_tokens":9223372036854775807,"output_tokens":1}}""" to TokenUsage(Long.MAX_VALUE, 1),
            """{"usage":{"total_tokens":9}}""" to TokenUsage(totalTokens = 9),
        )
        cases.forEach { (payload, expected) -> assertEquals(payload, expected, consume(payload).usage) }
    }

    @Test fun deepSeekCacheHitsAreRecordedAsPartOfInputUsage() {
        val result = consume("""{"usage":{"prompt_tokens":13,"completion_tokens":7,"prompt_cache_hit_tokens":9,"prompt_cache_miss_tokens":4}}""")
        assertEquals(TokenUsage(13, 7, 20, 9), result.usage)
    }

    @Test fun missingOrMalformedUsageAndJsonDoNotChangeTheOriginalResponse() {
        val cases = listOf(
            """{"choices":[]}""",
            """{"usage":null}""",
            """{"usage":[]}""",
            """{"usage":{"input_tokens":-1,"output_tokens":"invalid","total_tokens":1.5,"input_tokens_details":{"cached_tokens":{}}}}""",
            """{"usage":{"input_tokens":4,"output_tokens":""",
            "not JSON at all",
        )
        cases.forEach { payload -> assertNull(payload, consume(payload).usage) }
    }

    @Test fun multilineCrLfEventsSurviveUtf8ChunkBoundariesAndReportTheirUsage() {
        val payload = ": keepalive\r\n\r\n" +
            "event: response.completed\r\n" +
            "data: {\"response\": {\r\n" +
            "data: \"status\":\"completed\",\"output\":[{\"text\":\"你好\"}],\r\n" +
            "data: \"usage\":{\"input_tokens\":6,\"output_tokens\":2}\r\n" +
            "data: }}\r\n\r\n"
        val result = consume(payload, sse = true, chunkSize = 1)
        assertEquals(TokenUsage(6, 2, 8), result.usage)
        assertTrue(result.terminal)
        assertFalse(result.incomplete)
        assertNull(result.errorCode)
    }

    @Test fun jsonLargerThanObservationBudgetIsStillDeliveredAndDoesNotInventUsage() {
        val payload = "{\"choices\":[{\"message\":{\"content\":\"" + "x".repeat(4 * 1024 * 1024 + 128) +
            "\"}}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7,\"total_tokens\":18}}"
        val result = consume(payload, chunkSize = 8192)
        assertNull(result.usage)
        assertNull(result.errorCode)
    }

    @Test fun largeResponsesCompletionRetainsItsTerminalTypeWithoutTruncatingOutput() {
        val payload = "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"text\":\"" +
            "x".repeat(4 * 1024 * 1024 + 128) +
            "\"}],\"usage\":{\"input_tokens\":11,\"output_tokens\":7,\"total_tokens\":18}}}\n\n"
        val result = consume(payload, sse = true, chunkSize = 8192)
        assertTrue(result.terminal)
        assertFalse(result.incomplete)
        assertNull(result.usage)
        assertNull(result.errorCode)
    }

    @Test fun sseErrorsAndIncompleteResponsesHaveDistinctTerminalMetadata() {
        listOf(
            "event: error\ndata: {\"message\":\"private upstream detail\"}\n\n",
            "data: {\"type\":\"response.failed\",\"response\":{\"status\":\"failed\",\"error\":{\"message\":\"private upstream detail\"}}}\n\n",
        ).forEach { payload ->
            val result = consume(payload, sse = true)
            assertTrue(result.terminal)
            assertFalse(result.incomplete)
            assertEquals("upstream_response_failed", result.errorCode)
            assertNull(result.usage)
        }
        val incomplete = consume(
            "data: {\"type\":\"response.incomplete\",\"response\":{\"status\":\"incomplete\",\"usage\":{\"input_tokens\":3,\"output_tokens\":1}}}\n\n",
            sse = true,
        )
        assertTrue(incomplete.terminal)
        assertTrue(incomplete.incomplete)
        assertEquals("upstream_response_incomplete", incomplete.errorCode)
        assertEquals(TokenUsage(3, 1, 4), incomplete.usage)
    }
}
