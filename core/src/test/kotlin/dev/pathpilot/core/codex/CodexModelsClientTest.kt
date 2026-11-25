package dev.pathpilot.core.codex

import dev.pathpilot.core.network.OutboundProxyConfig
import dev.pathpilot.core.network.OutboundProxyMode
import dev.pathpilot.core.providers.ProviderProbeError
import dev.pathpilot.core.providers.ProviderProbeException
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

class CodexModelsClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: CodexModelsClient
    private val token = "fixture-access-token-never-shown"
    private val account = "fixture-account-never-shown"

    @Before fun setup() {
        server = MockWebServer().also { it.start() }
        client = localClient()
    }
    @After fun cleanup() { client.cancel(); server.shutdown() }

    private fun localClient(http: OkHttpClient = OkHttpClient()): CodexModelsClient = CodexModelsClient(
        http, { OutboundProxyConfig() },
        server.url("/backend-api/codex/models?client_version=${CodexModelsClient.CLIENT_VERSION}"),
    )
    private fun failure(block: () -> Unit): ProviderProbeException {
        try { block(); fail("Expected a redacted catalog error") } catch (error: ProviderProbeException) {
            assertFalse(error.toString().contains(token))
            assertFalse(error.toString().contains(account))
            assertNull(error.cause)
            return error
        }
        throw AssertionError("unreachable")
    }

    @Test fun productionRequestUsesCodexCatalogWithAccountAndVersionAndNoGenerationBody() {
        var requestedUrl = ""
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requestedUrl = chain.request().url.toString()
            chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath + "?" +
                chain.request().url.encodedQuery)).build())
        }.build()
        client = CodexModelsClient(http)
        server.enqueue(MockResponse().setBody("""{"models":[{"slug":"codex-subscription-model","visibility":"list"}]}"""))
        assertEquals(listOf("codex-subscription-model"), client.probe(token, account).models)
        assertEquals("https://chatgpt.com/backend-api/codex/models?client_version=0.172.0", requestedUrl)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/backend-api/codex/models?client_version=0.172.0", request.path)
        assertEquals("Bearer $token", request.getHeader("Authorization"))
        assertEquals(account, request.getHeader("ChatGPT-Account-ID"))
        assertEquals("codex_cli_rs", request.getHeader("originator"))
        assertEquals("application/json", request.getHeader("Accept"))
        assertTrue(request.getHeader("User-Agent")!!.startsWith("PathPilot/"))
        assertEquals(0, request.bodySize)
        assertEquals(1, server.requestCount)
    }

    @Test fun subscriptionListsVisibleSlugsIncludingModelsUnsupportedByApiKeys() {
        server.enqueue(MockResponse().setBody("""{"models":[
            {"slug":"subscription-only","display_name":"Display title","visibility":"list","supported_in_api":false,"available_in_plans":["plus"]},
            {"slug":"hidden","visibility":"hide","supported_in_api":true},
            {"slug":"none","visibility":"none","supported_in_api":true},
            {"slug":"api-and-subscription","visibility":"list","supported_in_api":true},
            {"slug":"subscription-only","visibility":"list","supported_in_api":false}
        ]}"""))
        assertEquals(listOf("subscription-only", "api-and-subscription"), client.probe(token, account).models)
    }

    @Test fun emptyOrEntirelyHiddenCatalogIsAnActualEmptyResult() {
        listOf("""{"models":[]}""", """{"models":[{"slug":"hidden","visibility":"hide"}]}""").forEach { body ->
            server.enqueue(MockResponse().setBody(body))
            assertEquals(emptyList<String>(), client.probe(token, account).models)
        }
    }

    @Test fun statusErrorsNeverEchoCredentialsOrPretendSuccess() {
        mapOf(401 to ProviderProbeError.UNAUTHORIZED, 403 to ProviderProbeError.FORBIDDEN,
            404 to ProviderProbeError.MODELS_UNSUPPORTED, 405 to ProviderProbeError.MODELS_UNSUPPORTED,
            429 to ProviderProbeError.RATE_LIMITED, 500 to ProviderProbeError.UPSTREAM).forEach { (status, expected) ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("$token $account"))
            val error = failure { client.probe(token, account) }
            assertEquals(expected, error.kind)
            if (status == 401) assertEquals("Codex 登录已过期，请重新登录", error.userMessage)
        }
        assertEquals(6, server.requestCount)
    }

    @Test fun malformedCatalogNeverFallsBackToHardCodedOrApiModels() {
        listOf("not-json-$token", "{}", "[]", """{"data":[{"id":"wrong-catalog"}]}""",
            """{"models":[{"slug":7,"visibility":"list"}]}""",
            """{"models":[{"slug":"bad model","visibility":"list"}]}""",
            """{"models":[{"slug":" trailing","visibility":"list"}]}""",
            """{"models":[{"slug":"model"}]}""",
            """{"models":[{"slug":"model","visibility":null}]}""",
            """{"models":[{"slug":"model","visibility":"unexpected"}]}""").forEach { body ->
            server.enqueue(MockResponse().setBody(body))
            assertEquals(ProviderProbeError.INVALID_RESPONSE, failure { client.probe(token, account) }.kind)
        }
    }

    @Test fun invalidCredentialsFailBeforeRequestAndNeverAppearInErrors() {
        listOf("" to account, "$token\n" to account, token to "", token to "$account\r\nheader: bad").forEach { (key, id) ->
            assertEquals(ProviderProbeError.INVALID_CONFIGURATION, failure { client.probe(key, id) }.kind)
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun redirectAndBrokenConnectionNeverRetryOrForwardCredentials() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/other")))
        assertEquals(ProviderProbeError.UPSTREAM, failure { client.probe(token, account) }.kind)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(ProviderProbeError.NETWORK, failure { client.probe(token, account) }.kind)
        assertEquals(2, server.requestCount)
    }

    @Test fun bodyAndDistinctModelCountLimitsDoNotSilentlyTruncate() {
        val oversized = "x".repeat(CodexModelsClient.MAX_RESPONSE_BYTES + 1)
        server.enqueue(MockResponse().setBody(oversized))
        assertEquals(ProviderProbeError.RESPONSE_TOO_LARGE, failure { client.probe(token, account) }.kind)
        server.enqueue(MockResponse().setChunkedBody(oversized, 4096))
        assertEquals(ProviderProbeError.RESPONSE_TOO_LARGE, failure { client.probe(token, account) }.kind)
        val models = (1..501).joinToString(",") { """{"slug":"model-$it","visibility":"list"}""" }
        server.enqueue(MockResponse().setBody("{\"models\":[$models]}"))
        assertEquals(ProviderProbeError.RESPONSE_TOO_LARGE, failure { client.probe(token, account) }.kind)
    }

    @Test fun realisticNestedInstructionCatalogLargerThanOneMiBStillReturnsModelSlugs() {
        // Official rust-v0.172.0 ships 14 models / 667,893 bytes; the largest model_messages
        // is ~63 KiB. Reproduce that nested shape with synthetic text, without copying prompts.
        val text = "x".repeat(1024)
        val body = buildJsonObject {
            put("models", buildJsonArray {
                for (id in 1..24) add(buildJsonObject {
                    put("slug", "fixture-model-$id")
                    put("visibility", if (id == 24) "hide" else "list")
                    put("supported_in_api", false)
                    put("base_instructions", text.repeat(4))
                    put("model_messages", buildJsonObject {
                        put("instructions_template", text.repeat(21))
                        put("persistent_instructions", text.repeat(6))
                        put("confirmation_policies", buildJsonObject {
                            put("browser_use", text.repeat(12))
                            put("computer_use", text.repeat(12))
                        })
                        put("guardian_v2", buildJsonObject { put("classifier_instructions", text.repeat(4)) })
                        put("token_budget", buildJsonObject {
                            put("enabled", true)
                            put("reminder_message_template", text.repeat(2))
                            put("guidance_message", text.repeat(2))
                        })
                    })
                })
            })
        }.toString()
        assertTrue(body.toByteArray(Charsets.UTF_8).size > 1024 * 1024)
        assertTrue(body.toByteArray(Charsets.UTF_8).size < CodexModelsClient.MAX_RESPONSE_BYTES)
        for (response in listOf(MockResponse().setBody(body), MockResponse().setChunkedBody(body, 8192))) {
            server.enqueue(response)
            assertEquals((1..23).map { "fixture-model-$it" }, client.probe(token, account).models)
        }
    }

    @Test fun timeoutIsDistinctFromUserCancellation() {
        client = localClient(OkHttpClient.Builder().callTimeout(100, TimeUnit.MILLISECONDS).build())
        server.enqueue(MockResponse().setBody("{\"models\":[]}").setBodyDelay(500, TimeUnit.MILLISECONDS))
        assertEquals(ProviderProbeError.TIMEOUT, failure { client.probe(token, account) }.kind)
    }

    @Test fun cancelStopsActiveAndNotYetStartedCatalogRequests() {
        val workers = Executors.newSingleThreadExecutor()
        try {
            server.enqueue(MockResponse().setBody("{\"models\":[]}").setBodyDelay(2, TimeUnit.SECONDS))
            val result = workers.submit<ProviderProbeError> { failure { client.probe(token, account) }.kind }
            assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            client.cancel()
            assertEquals(ProviderProbeError.CANCELLED, result.get(1, TimeUnit.SECONDS))
            assertEquals(ProviderProbeError.CANCELLED, failure { client.probe(token, account) }.kind)
            assertEquals(1, server.requestCount)
        } finally { workers.shutdownNow() }
    }

    @Test fun explicitProxyIsAppliedAndItsAuthenticationErrorIsRedacted() {
        server.enqueue(MockResponse().setBody("""{"models":[{"slug":"proxy-model","visibility":"list"}]}"""))
        client = CodexModelsClient(OkHttpClient(), {
            OutboundProxyConfig(OutboundProxyMode.HTTP, "127.0.0.1", server.port)
        }, server.url("/models").newBuilder().host("codex-fixture.invalid").build())
        assertEquals(listOf("proxy-model"), client.probe(token, account).models)
        assertTrue(server.takeRequest().requestLine.startsWith("GET http://codex-fixture.invalid:"))
        server.enqueue(MockResponse().setResponseCode(407).setHeader("Proxy-Authenticate", "Basic realm=proxy").setBody(token))
        val error = failure { client.probe(token, account) }
        assertEquals(ProviderProbeError.UPSTREAM, error.kind)
        assertEquals("代理需要认证，当前仅支持无认证代理", error.userMessage)
    }
}
