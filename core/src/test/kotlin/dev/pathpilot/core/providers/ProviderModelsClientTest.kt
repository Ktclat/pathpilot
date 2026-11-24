package dev.pathpilot.core.providers

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProviderModelsClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ProviderModelsClient
    private val secret = "secret-that-must-never-be-in-errors"

    @Before fun setup() {
        server = MockWebServer().also { it.start() }
        client = ProviderModelsClient(OkHttpClient(), allowLocalHttpTests = true)
    }
    @After fun cleanup() { client.cancel(); server.shutdown() }
    private fun baseUrl() = server.url("/v1").toString()
    private fun failure(block: () -> Unit): ProviderProbeException {
        try { block(); fail("Expected a redacted probe error") } catch (error: ProviderProbeException) {
            assertFalse(error.toString().contains(secret)); assertNull(error.cause); return error
        }
        throw AssertionError("unreachable")
    }

    @Test fun probesOnlyModelsWithIndependentCredentialAndSortedUniqueIds() {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"z-model"},{"id":"a-model"},{"id":"z-model"}]}"""))
        val result = client.probe(baseUrl() + "/", " $secret ")
        assertEquals(listOf("a-model", "z-model"), result.models)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/v1/models", request.path)
        assertEquals("Bearer $secret", request.getHeader("Authorization"))
        assertEquals(0, request.bodySize)
        assertEquals(1, server.requestCount)
    }

    @Test fun statusErrorsAreSpecificRedactedAndPermitManualModels() {
        mapOf(401 to ProviderProbeError.UNAUTHORIZED, 403 to ProviderProbeError.FORBIDDEN,
            404 to ProviderProbeError.MODELS_UNSUPPORTED, 405 to ProviderProbeError.MODELS_UNSUPPORTED,
            429 to ProviderProbeError.RATE_LIMITED, 500 to ProviderProbeError.UPSTREAM).forEach { (status, expected) ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("\"$secret\""))
            val error = failure { client.probe(baseUrl(), secret) }
            assertEquals(expected, error.kind)
            if (status == 404) assertTrue(error.userMessage.contains("仍可手动"))
        }
        assertEquals(6, server.requestCount)
    }

    @Test fun productionEntryRejectsHttpBeforeSendingCredential() {
        val production = ProviderModelsClient()
        assertEquals(ProviderProbeError.INVALID_CONFIGURATION, failure { production.probe(baseUrl(), secret) }.kind)
        assertEquals(0, server.requestCount)
    }

    @Test fun testHttpExceptionIsOnlyForLoopback() {
        assertEquals(ProviderProbeError.INVALID_CONFIGURATION,
            failure { client.probe("http://example.com/v1", secret) }.kind)
        assertEquals(0, server.requestCount)
    }

    @Test fun redirectsNeverForwardCredentialOrRetry() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/other")))
        assertEquals(ProviderProbeError.UPSTREAM, failure { client.probe(baseUrl(), secret) }.kind)
        assertEquals(1, server.requestCount)
    }

    @Test fun brokenConnectionIsRedactedAndNotRetried() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(ProviderProbeError.NETWORK, failure { client.probe(baseUrl(), secret) }.kind)
        assertEquals(1, server.requestCount)
    }

    @Test fun malformedJsonAndInvalidModelShapesAreRejected() {
        listOf("not-json-$secret", "{}", "[]", """{"data":[{"id":1}]}""", """{"data":[{"id":"bad model"}]}""",
            """{"data":[{"id":" trailing"}]}""", """{"data":[{}]}""").forEach { body ->
            server.enqueue(MockResponse().setBody(body))
            assertEquals(ProviderProbeError.INVALID_RESPONSE, failure { client.probe(baseUrl(), secret) }.kind)
        }
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        assertEquals(emptyList<String>(), client.probe(baseUrl(), secret).models)
    }

    @Test fun responseLimitAppliesToContentLengthAndChunkedBodies() {
        val body = "x".repeat(ProviderModelsClient.MAX_RESPONSE_BYTES + 1)
        server.enqueue(MockResponse().setBody(body))
        assertEquals(ProviderProbeError.RESPONSE_TOO_LARGE, failure { client.probe(baseUrl(), secret) }.kind)
        server.enqueue(MockResponse().setChunkedBody(body, 4096))
        assertEquals(ProviderProbeError.RESPONSE_TOO_LARGE, failure { client.probe(baseUrl(), secret) }.kind)
    }

    @Test fun modelCountLimitIsDistinctAndNeverSilentlyTruncates() {
        val models = (1..501).joinToString(",") { "{\"id\":\"model-$it\"}" }
        server.enqueue(MockResponse().setBody("{\"data\":[$models]}"))
        assertEquals(ProviderProbeError.RESPONSE_TOO_LARGE, failure { client.probe(baseUrl(), secret) }.kind)
        val duplicates = (1..501).joinToString(",") { "{\"id\":\"same\"}" }
        server.enqueue(MockResponse().setBody("{\"data\":[$duplicates]}"))
        assertEquals(listOf("same"), client.probe(baseUrl(), secret).models)
    }

    @Test fun wholeCallTimeoutIsNotMisreportedAsUserCancellation() {
        client = ProviderModelsClient(OkHttpClient.Builder().callTimeout(100, TimeUnit.MILLISECONDS).build(), true)
        server.enqueue(MockResponse().setBody("{\"data\":[]}").setBodyDelay(500, TimeUnit.MILLISECONDS))
        assertEquals(ProviderProbeError.TIMEOUT, failure { client.probe(baseUrl(), secret) }.kind)
    }

    @Test fun cancelStopsActiveOrNotYetStartedProbe() {
        val workers = Executors.newSingleThreadExecutor()
        try {
            server.enqueue(MockResponse().setBody("{\"data\":[]}").setBodyDelay(2, TimeUnit.SECONDS))
            val future = workers.submit<ProviderProbeError> { failure { client.probe(baseUrl(), secret) }.kind }
            assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            client.cancel()
            assertEquals(ProviderProbeError.CANCELLED, future.get(1, TimeUnit.SECONDS))
            assertEquals(ProviderProbeError.CANCELLED, failure { client.probe(baseUrl(), secret) }.kind)
            assertEquals(1, server.requestCount)
        } finally { workers.shutdownNow() }
    }
}
