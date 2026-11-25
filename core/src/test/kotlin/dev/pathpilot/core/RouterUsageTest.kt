package dev.pathpilot.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Observes the gateway over real HTTP, including consumption and cancellation of response bodies. */
class RouterUsageTest {
    private lateinit var upstream: MockWebServer
    private lateinit var router: RouterServer
    private lateinit var config: RouterConfig
    private val events = LinkedBlockingQueue<RequestEvent>()
    private val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    private val key = AccessKeys.generate()
    private val chatPath = "/v1/chat/completions"

    @Before fun setup() {
        upstream = MockWebServer().also { it.start() }
        config = RouterConfig(
            port = 0,
            accessKey = key,
            providers = listOf(ProviderConfig("source", "DeepSeek Test", ProviderKind.DEEPSEEK,
                upstream.url("/v1").toString(), "private-upstream-key")),
            routes = listOf(RouteConfig("daily", "source", "deepseek-chat")),
        )
        startRouter()
    }

    @After fun cleanup() {
        router.stop()
        upstream.shutdown()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private fun startRouter(upstreamClient: OkHttpClient = RouterServer.defaultClient()) {
        router = RouterServer({ config }, upstreamClient, onRequest = { events.add(it) },
            codexCredentials = { CodexCredentials("private-codex-token", "account-fixture") })
            .also { it.start(5_000, true) }
    }

    private fun request(
        path: String = chatPath,
        body: String? = """{"model":"daily","messages":[{"role":"user","content":"private-prompt"}]}""",
        authorization: String? = key,
    ): Response {
        val builder = Request.Builder().url("http://127.0.0.1:${router.listeningPort}$path")
        authorization?.let { builder.header("Authorization", "Bearer $it") }
        body?.let { builder.post(it.toRequestBody("application/json".toMediaType())) }
        return client.newCall(builder.build()).execute()
    }

    private fun event(): RequestEvent = checkNotNull(events.poll(5, TimeUnit.SECONDS)) {
        "Expected one completed request event"
    }

    private fun assertNoMoreEvents() {
        assertNull("A request must not be counted again when its response closes", events.poll(100, TimeUnit.MILLISECONDS))
    }

    @Test fun chatJsonRecordsActualUsageAndRouteIdentityWithoutContent() {
        val body = """{"choices":[{"message":{"content":"private-answer"}}],"usage":{"prompt_tokens":11,"completion_tokens":7,"total_tokens":18,"prompt_tokens_details":{"cached_tokens":4},"completion_tokens_details":{"reasoning_tokens":2}}}"""
        upstream.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
        val before = System.currentTimeMillis()
        request().use { assertEquals(200, it.code); assertEquals(body, it.body!!.string()) }

        val observed = event()
        assertEquals("daily", observed.model)
        assertEquals("source", observed.providerId)
        assertEquals("DeepSeek Test", observed.providerName)
        assertEquals("deepseek-chat", observed.upstreamModel)
        assertEquals(chatPath, observed.endpoint)
        assertFalse(observed.streaming)
        assertEquals(200, observed.status)
        assertEquals(RequestOutcome.SUCCESS, observed.outcome)
        assertNull(observed.errorCode)
        assertEquals(TokenUsage(11, 7, 18, 4, 2), observed.usage)
        assertTrue(observed.id.isNotBlank())
        assertTrue(observed.startedAtEpochMs in before..System.currentTimeMillis())
        assertTrue(observed.durationMs >= 0)
        val serialized = Json.encodeToString(observed)
        listOf("private-prompt", "private-answer", "private-upstream-key", key).forEach {
            assertFalse("Request metadata must not contain $it", serialized.contains(it))
        }
        assertNoMoreEvents()
    }

    @Test fun absentUsageStaysUnknownAndDoesNotBecomeZeroTokens() {
        upstream.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"answer"}}]}"""))
        request().use { assertEquals(200, it.code); it.body!!.string() }
        val observed = event()
        assertEquals(RequestOutcome.SUCCESS, observed.outcome)
        assertNull(observed.usage)
        assertNoMoreEvents()
    }

    @Test fun chatSseKeepsBytesAndRequestOptionsWhileReadingTheFinalUsageChunk() {
        val body = "data: {\"choices\":[{\"delta\":{\"content\":\"中文\"}}],\"usage\":null}\r\n\r\n" +
            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":4,\"total_tokens\":13}}\r\n\r\n" +
            "data: [DONE]\r\n\r\n"
        upstream.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setChunkedBody(body, 3))
        request(body = """{"model":"daily","stream":true,"messages":[]}""").use {
            assertEquals(200, it.code)
            assertNull(it.header("Content-Encoding"))
            assertEquals(body, it.body!!.string())
        }
        val forwarded = Json.parseToJsonElement(upstream.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
        assertFalse("Observation must not opt clients into a new response format", forwarded.containsKey("stream_options"))
        val observed = event()
        assertTrue(observed.streaming)
        assertEquals(TokenUsage(9, 4, 13), observed.usage)
        assertEquals(RequestOutcome.SUCCESS, observed.outcome)
        assertNoMoreEvents()
    }

    @Test fun responsesSseReadsNestedUsageWithoutChangingTheStream() {
        config = config.copy(providers = config.providers.map { it.copy(kind = ProviderKind.OPENAI_COMPATIBLE) })
        val body = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"answer\"}\n\n" +
            "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":20,\"output_tokens\":8,\"total_tokens\":28,\"input_tokens_details\":{\"cached_tokens\":6},\"output_tokens_details\":{\"reasoning_tokens\":3}}}}\n\n"
        upstream.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setChunkedBody(body, 11))
        request("/v1/responses", """{"model":"daily","stream":true,"input":"hello"}""").use {
            assertEquals(200, it.code); assertEquals(body, it.body!!.string())
        }
        val observed = event()
        assertEquals("/v1/responses", observed.endpoint)
        assertEquals(TokenUsage(20, 8, 28, 6, 3), observed.usage)
        assertEquals(RequestOutcome.SUCCESS, observed.outcome)
        assertNoMoreEvents()
    }

    private fun useCodexProvider() {
        router.stop()
        config = config.copy(providers = listOf(ProviderConfig("source", "Codex Test", ProviderKind.CODEX, "unused")),
            routes = listOf(RouteConfig("daily", "source", "codex-fixture-model")))
        startRouter(RouterServer.defaultClient().newBuilder().addInterceptor { chain ->
            assertEquals("Bearer private-codex-token", chain.request().header("Authorization"))
            chain.proceed(chain.request().newBuilder().url(upstream.url("/responses")).build())
        }.build())
    }

    private fun codexCompletion() = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"answer\"}\n\n" +
        "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_fixture\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"answer\"}]}],\"usage\":{\"input_tokens\":12,\"output_tokens\":5,\"total_tokens\":17}}}\n\n"

    @Test fun codexAggregationKeepsUsageWhenReturningNonStreamingChat() {
        useCodexProvider()
        upstream.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(codexCompletion()))
        request().use {
            assertEquals(200, it.code)
            val body = Json.parseToJsonElement(it.body!!.string()).jsonObject
            assertEquals(17L, body["usage"]!!.jsonObject["total_tokens"]!!.jsonPrimitive.long)
        }
        val observed = event()
        assertFalse(observed.streaming)
        assertEquals("Codex Test", observed.providerName)
        assertEquals("codex-fixture-model", observed.upstreamModel)
        assertEquals(TokenUsage(12, 5, 17), observed.usage)
        assertEquals(RequestOutcome.SUCCESS, observed.outcome)
        assertNoMoreEvents()
    }

    @Test fun codexChatStreamObservesUsageEvenWhenClientDidNotRequestUsageChunks() {
        useCodexProvider()
        upstream.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(codexCompletion()))
        request(body = """{"model":"daily","stream":true,"messages":[{"role":"user","content":"hello"}]}""").use {
            assertEquals(200, it.code)
            val body = it.body!!.string()
            assertTrue(body.contains("data: [DONE]"))
            assertFalse("Do not add usage chunks the client did not ask for", body.contains("\"usage\""))
        }
        val observed = event()
        assertTrue(observed.streaming)
        assertEquals(TokenUsage(12, 5, 17), observed.usage)
        assertEquals(RequestOutcome.SUCCESS, observed.outcome)
        assertNoMoreEvents()
    }

    @Test fun upstreamHttpErrorRecordsStatusWithoutTheUpstreamErrorBody() {
        upstream.enqueue(MockResponse().setResponseCode(429).setBody("private-upstream-error-body private-upstream-key"))
        request().use {
            assertEquals(429, it.code)
            assertFalse(it.body!!.string().contains("private-upstream-error-body"))
        }
        val observed = event()
        assertEquals(429, observed.status)
        assertEquals(RequestOutcome.HTTP_ERROR, observed.outcome)
        assertEquals("upstream_error", observed.errorCode)
        assertNull(observed.usage)
        assertFalse(Json.encodeToString(observed).contains("private-upstream"))
        assertNoMoreEvents()
    }

    @Test fun authenticatedModelFailuresAreVisibleBeforeAnyUpstreamRequest() {
        request(body = """{"messages":[]}""").use { assertEquals(400, it.code); it.body!!.string() }
        assertEquals("model_required", event().errorCode)

        request(body = """{"model":"unknown","messages":[]}""").use { assertEquals(404, it.code); it.body!!.string() }
        val unknown = event()
        assertEquals("unknown", unknown.model)
        assertEquals(RequestOutcome.HTTP_ERROR, unknown.outcome)
        assertEquals("model_not_found", unknown.errorCode)

        config = config.copy(routes = config.routes.map { it.copy(enabled = false) })
        request().use { assertEquals(404, it.code); it.body!!.string() }
        assertEquals("model_not_found", event().errorCode)

        config = config.copy(routes = config.routes.map { it.copy(enabled = true) },
            providers = config.providers.map { it.copy(enabled = false) })
        request().use { assertEquals(503, it.code); it.body!!.string() }
        val disabled = event()
        assertEquals("source", disabled.providerId)
        assertEquals(RequestOutcome.HTTP_ERROR, disabled.outcome)
        assertEquals("provider_unavailable", disabled.errorCode)
        assertEquals(0, upstream.requestCount)
        assertNoMoreEvents()
    }

    @Test fun healthModelDiscoveryAndRejectedAuthenticationDoNotInflateModelUsage() {
        request("/health", body = null, authorization = null).use { assertEquals(200, it.code); it.body!!.string() }
        request("/v1/models", body = null).use { assertEquals(200, it.code); it.body!!.string() }
        request(authorization = null).use { assertEquals(401, it.code); it.body!!.string() }
        assertEquals(0, upstream.requestCount)
        assertNoMoreEvents()
    }

    private fun longStream(): Response {
        upstream.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"more\"}}]}\n\n".repeat(100_000))
            .throttleBody(8192, 25, TimeUnit.MILLISECONDS))
        return request(body = """{"model":"daily","stream":true,"messages":[]}""").also {
            assertEquals(200, it.code)
            assertTrue(it.body!!.byteStream().read() >= 0)
        }
    }

    @Test fun downstreamCloseReportsInterruptedInsteadOfSuccessfulHttp200() {
        longStream().close()
        val observed = event()
        assertEquals(200, observed.status)
        assertEquals(RequestOutcome.INTERRUPTED, observed.outcome)
        assertNull(observed.usage)
        assertNoMoreEvents()
    }

    @Test fun stoppingRouterReportsTheActiveStreamExactlyOnceAsInterrupted() {
        val response = longStream()
        try {
            router.stop()
            val observed = event()
            assertEquals(200, observed.status)
            assertEquals(RequestOutcome.INTERRUPTED, observed.outcome)
        } finally { response.close() }
        assertNoMoreEvents()
    }

    @Test fun sseEofWithoutACompletionMarkerIsNotCountedAsSuccess() {
        val body = "data: {\"choices\":[{\"delta\":{\"content\":\"unfinished\"}}]}\n\n"
        upstream.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
        request(body = """{"model":"daily","stream":true,"messages":[]}""").use {
            assertEquals(200, it.code); assertEquals(body, it.body!!.string())
        }
        val observed = event()
        assertEquals(200, observed.status)
        assertEquals(RequestOutcome.INTERRUPTED, observed.outcome)
        assertNull(observed.usage)
        assertNoMoreEvents()
    }

    @Test fun upstreamErrorAndIncompleteEventsKeepHttp200ButHaveTheirActualOutcomes() {
        config = config.copy(providers = config.providers.map { it.copy(kind = ProviderKind.OPENAI_COMPATIBLE) })
        val failed = "data: {\"type\":\"response.failed\",\"response\":{\"status\":\"failed\",\"error\":{\"message\":\"private-error-detail\"}}}\n\n"
        upstream.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(failed))
        request("/v1/responses", """{"model":"daily","stream":true,"input":"hello"}""").use {
            assertEquals(200, it.code); assertEquals(failed, it.body!!.string())
        }
        val error = event()
        assertEquals(200, error.status)
        assertEquals(RequestOutcome.HTTP_ERROR, error.outcome)
        assertEquals("upstream_response_failed", error.errorCode)
        assertFalse(Json.encodeToString(error).contains("private-error-detail"))

        val incomplete = "data: {\"type\":\"response.incomplete\",\"response\":{\"status\":\"incomplete\",\"usage\":{\"input_tokens\":4,\"output_tokens\":2}}}\n\n"
        upstream.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(incomplete))
        request("/v1/responses", """{"model":"daily","stream":true,"input":"hello"}""").use {
            assertEquals(200, it.code); assertEquals(incomplete, it.body!!.string())
        }
        val partial = event()
        assertEquals(200, partial.status)
        assertEquals(RequestOutcome.INTERRUPTED, partial.outcome)
        assertEquals("upstream_response_incomplete", partial.errorCode)
        assertEquals(TokenUsage(4, 2, 6), partial.usage)
        assertNoMoreEvents()
    }
}
