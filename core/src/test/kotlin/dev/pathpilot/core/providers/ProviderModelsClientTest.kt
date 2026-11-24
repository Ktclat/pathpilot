package dev.pathpilot.core.providers

import okhttp3.OkHttpClient
import dev.pathpilot.core.network.OutboundProxyConfig
import dev.pathpilot.core.network.OutboundProxyMode
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
        client = ProviderModelsClient(OkHttpClient())
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
            if (status == 404) assertEquals("此服务未提供模型列表", error.userMessage)
        }
        assertEquals(6, server.requestCount)
    }

    @Test fun proxyAuthenticationFailureDoesNotMasqueradeAsApiKeyOrModelSuccess() {
        server.enqueue(MockResponse().setResponseCode(407)
            .setHeader("Proxy-Authenticate", "Basic realm=proxy").setBody(secret))
        val proxied = ProviderModelsClient(proxyProvider = {
            OutboundProxyConfig(OutboundProxyMode.HTTP, "127.0.0.1", server.port)
        })
        val error = failure { proxied.probe("http://provider.invalid/v1", secret) }
        assertEquals(ProviderProbeError.UPSTREAM, error.kind)
        assertEquals("代理需要认证，当前仅支持无认证代理", error.userMessage)
        assertEquals(1, server.requestCount)
    }

    @Test fun productionEntrySupportsExplicitLocalHttpUpstream() {
        val production = ProviderModelsClient()
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"local-ollama"}]}"""))
        assertEquals(listOf("local-ollama"), production.probe(baseUrl(), secret).models)
        assertEquals("Bearer $secret", server.takeRequest().getHeader("Authorization"))
        assertEquals(1, server.requestCount)
    }

    @Test fun unauthenticatedHttpModelDiscoveryOmitsAuthorizationEntirely() {
        for (emptyKey in listOf("", "  ")) {
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"local-ollama"}]}"""))
            assertEquals(listOf("local-ollama"), client.probe(baseUrl(), emptyKey).models)
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method)
            assertEquals("/v1/models", request.path)
            assertNull(request.getHeader("Authorization"))
        }
    }

    @Test fun nonHttpProtocolsAreRejectedBeforeSendingCredentials() {
        assertEquals(ProviderProbeError.INVALID_CONFIGURATION,
            failure { client.probe("file:///tmp/models", secret) }.kind)
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
        client = ProviderModelsClient(OkHttpClient.Builder().callTimeout(100, TimeUnit.MILLISECONDS).build())
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
