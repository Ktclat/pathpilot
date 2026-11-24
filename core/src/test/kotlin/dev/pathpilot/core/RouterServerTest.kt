package dev.pathpilot.core

import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody
import okio.BufferedSink
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

class RouterServerTest {
    private lateinit var upstream: MockWebServer
    private lateinit var router: RouterServer
    private lateinit var config: RouterConfig
    private val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    private val key = AccessKeys.generate()

    @Before fun setup() {
        upstream = MockWebServer().also { it.start() }
        config = RouterConfig(0, key, listOf(ProviderConfig("deepseek", "DeepSeek", ProviderKind.DEEPSEEK, upstream.url("/v1").toString(), "secret-upstream")),
            listOf(RouteConfig("fast", "deepseek", "deepseek-chat")))
        router = RouterServer({ config }).also { it.start(5_000, true) }
    }

    @After fun cleanup() { router.stop(); upstream.shutdown() }

    private fun request(path: String, body: String? = null, authorization: String? = key): okhttp3.Response {
        val builder = Request.Builder().url("http://127.0.0.1:${router.listeningPort}$path")
        authorization?.let { builder.header("Authorization", "Bearer $it") }
        body?.let { builder.post(it.toRequestBody("application/json".toMediaType())) }
        return client.newCall(builder.build()).execute()
    }

    @Test fun healthIsMinimalAndModelsRequireIndependentKey() {
        request("/health", authorization = null).use { assertEquals(200, it.code); assertEquals("{\"status\":\"ok\"}", it.body!!.string()) }
        request("/v1/models", authorization = null).use { assertEquals(401, it.code) }
        request("/v1/models", authorization = "secret-upstream").use { assertEquals(401, it.code) }
        request("/v1/models").use {
            assertEquals(200, it.code)
            val body = it.body!!.string()
            assertTrue(body.contains("fast")); assertFalse(body.contains("secret-upstream"))
        }
    }

    @Test fun routesChatAndKeepsUpstreamCredentialOffLan() {
        upstream.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"choices\":[{\"message\":{\"content\":\"hello\"}}]}"))
        request("/v1/chat/completions", """{"model":"fast","messages":[{"role":"user","content":"hello"}]}""").use {
            assertEquals(200, it.code); assertTrue(it.body!!.string().contains("hello"))
        }
        val forwarded = upstream.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/v1/chat/completions", forwarded.path)
        assertEquals("Bearer secret-upstream", forwarded.getHeader("Authorization"))
        assertEquals("deepseek-chat", Json.parseToJsonElement(forwarded.body.readUtf8()).jsonObject["model"]!!.jsonPrimitive.content)
    }

    @Test fun unauthenticatedCompatibleHttpUpstreamStillRequiresLocalAccessKey() {
        config = config.copy(providers = config.providers.map { it.copy(kind = ProviderKind.OPENAI_COMPATIBLE, apiKey = "") })
        request("/v1/chat/completions", """{"model":"fast","messages":[]}""", authorization = null).use { assertEquals(401, it.code) }
        assertEquals(0, upstream.requestCount)
        upstream.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"local answer"}}]}"""))
        request("/v1/chat/completions", """{"model":"fast","messages":[{"role":"user","content":"hello"}]}""").use {
            assertEquals(200, it.code)
            assertTrue(it.body!!.string().contains("local answer"))
        }
        val forwarded = upstream.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/chat/completions", forwarded.path)
        assertNull(forwarded.getHeader("Authorization"))
    }

    @Test fun deepSeekStillRejectsMissingApiKeyBeforeUpstream() {
        config = config.copy(providers = config.providers.map { it.copy(apiKey = "") })
        request("/v1/chat/completions", """{"model":"fast","messages":[]}""").use {
            assertEquals(503, it.code)
            assertTrue(it.body!!.string().contains("api_key_required"))
        }
        assertEquals(0, upstream.requestCount)
    }

    @Test fun streamsSseWithoutCollectingAndDoesNotRetry() {
        val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"one\"}}]}\n\ndata: [DONE]\n\n"
        upstream.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setChunkedBody(sse, 7))
        request("/v1/chat/completions", """{"model":"fast","stream":true,"messages":[{"role":"user","content":"hi"}]}""").use {
            assertEquals(200, it.code); assertTrue(it.header("Content-Type")!!.startsWith("text/event-stream")); assertEquals(sse, it.body!!.string())
        }
        assertEquals(1, upstream.requestCount)
    }

    @Test fun unknownDisabledAndUnsupportedRoutesFailBeforeUpstream() {
        request("/v1/chat/completions", """{"model":"unknown","messages":[]}""").use { assertEquals(404, it.code) }
        request("/v1/responses", """{"model":"fast","input":"hello"}""").use { assertEquals(400, it.code) }
        config = config.copy(providers = config.providers.map { it.copy(enabled = false) })
        request("/v1/models").use { assertEquals("[]", Json.parseToJsonElement(it.body!!.string()).jsonObject["data"].toString()) }
        request("/v1/chat/completions", """{"model":"fast","messages":[]}""").use { assertEquals(503, it.code) }
        assertEquals(0, upstream.requestCount)
    }

    @Test fun malformedAndOversizedBodiesAreRejected() {
        request("/v1/chat/completions", "{").use { assertEquals(400, it.code) }
        request("/v1/chat/completions", "x".repeat(2 * 1024 * 1024 + 1)).use { assertEquals(413, it.code) }
        assertEquals(0, upstream.requestCount)
    }

    @Test fun earlyRejectionsCloseUnreadBodiesAndTheSameClientCanContinue() {
        val payload = """{"model":"fast","messages":[]}"""
        fun builder(path: String = "/v1/chat/completions") = Request.Builder()
            .url("http://127.0.0.1:${router.listeningPort}$path")
            .header("Authorization", "Bearer $key")
            .post(payload.toRequestBody("application/json".toMediaType()))
        val chunked = object : RequestBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun writeTo(sink: BufferedSink) { sink.writeUtf8(payload) }
        }
        val cases = listOf(
            builder().removeHeader("Authorization").build() to 401,
            builder("/unknown").build() to 404,
            builder().method("PUT", payload.toRequestBody("application/json".toMediaType())).build() to 405,
            builder().post(payload.toRequestBody("text/plain".toMediaType())).build() to 415,
            builder().header("Content-Encoding", "gzip").build() to 415,
            builder().post("".toRequestBody("application/json".toMediaType())).build() to 400,
            builder().post(chunked).build() to 411,
            builder().post("{".toRequestBody("application/json".toMediaType())).build() to 400,
        )
        for ((invalid, status) in cases) {
            client.newCall(invalid).execute().use {
                assertEquals(status, it.code)
                assertEquals("close", it.header("Connection"))
                it.body!!.string()
            }
            request("/v1/models").use { assertEquals(200, it.code); assertTrue(it.body!!.string().contains("fast")) }
        }
        assertEquals(0, upstream.requestCount)
    }

    @Test fun errorsAfterCompleteBodyReadKeepConnectionUsable() {
        request("/v1/chat/completions", """{"model":"unknown","messages":[]}""").use {
            assertEquals(404, it.code)
            assertNotEquals("close", it.header("Connection"))
            it.body!!.string()
        }
        upstream.enqueue(MockResponse().setResponseCode(429).setBody("rate limited"))
        request("/v1/chat/completions", """{"model":"fast","messages":[]}""").use {
            assertEquals(429, it.code)
            assertNotEquals("close", it.header("Connection"))
            it.body!!.string()
        }
        request("/v1/models").use { assertEquals(200, it.code); it.body!!.string() }
    }

    @Test fun upstreamErrorBodyIsRedactedAndStatusPreserved() {
        upstream.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2").setBody("debug: secret-upstream"))
        request("/v1/chat/completions", """{"model":"fast","messages":[]}""").use {
            assertEquals(429, it.code); assertEquals("2", it.header("Retry-After")); assertFalse(it.body!!.string().contains("secret-upstream"))
        }
        assertEquals(1, upstream.requestCount)
    }

    @Test fun changedLocalKeyTakesEffectWithoutRestart() {
        val replacement = AccessKeys.generate()
        assertNotEquals(key, replacement)
        config = config.copy(accessKey = replacement)
        request("/v1/models").use { assertEquals(401, it.code) }
        request("/v1/models", authorization = replacement).use { assertEquals(200, it.code) }
    }

    @Test fun responsesCompatibleProviderUsesResponsesPath() {
        config = config.copy(providers = config.providers.map { it.copy(kind = ProviderKind.OPENAI_COMPATIBLE) })
        upstream.enqueue(MockResponse().setBody("{\"id\":\"resp_123\",\"output\":[]}"))
        request("/v1/responses", """{"model":"fast","input":"hello"}""").use { assertEquals(200, it.code); assertTrue(it.body!!.string().contains("resp_123")) }
        assertEquals("/v1/responses", upstream.takeRequest(2, TimeUnit.SECONDS)!!.path)
    }

    @Test fun redirectsDoNotLeakCredentialsOrReplayGeneration() {
        upstream.enqueue(MockResponse().setResponseCode(307).setHeader("Location", upstream.url("/elsewhere")))
        request("/v1/chat/completions", """{"model":"fast","messages":[]}""").use { assertEquals(307, it.code) }
        assertEquals(1, upstream.requestCount)
    }

    @Test fun closingLanStreamReleasesUpstreamAndReportsCompletion() {
        router.stop()
        val released = CountDownLatch(1)
        router = RouterServer({ config }, onRequest = { released.countDown() }).also { it.start(5_000, true) }
        upstream.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"delta\":\"text\"}\n\n".repeat(100_000))
            .throttleBody(8192, 25, TimeUnit.MILLISECONDS))
        val response = request("/v1/chat/completions", """{"model":"fast","stream":true,"messages":[]}""")
        assertEquals(200, response.code)
        assertNull("SSE must not be gzip-buffered", response.header("Content-Encoding"))
        assertTrue(response.body!!.byteStream().read() >= 0)
        response.close()
        assertTrue("Gateway must close the upstream on downstream disconnect", released.await(5, TimeUnit.SECONDS))
    }

    @Test fun credentialResolverTimeoutIsReturnedAsGatewayTimeout() {
        router.stop()
        config = config.copy(providers = config.providers.map { it.copy(kind = ProviderKind.CODEX) })
        router = RouterServer({ config }, codexCredentials = { throw java.net.SocketTimeoutException("test") }).also { it.start(5_000, true) }
        request("/v1/responses", """{"model":"fast","input":"hello"}""").use { assertEquals(504, it.code) }
        assertEquals(0, upstream.requestCount)
    }

    @Test fun admissionLimitRejectsNinthActiveRequestAndRecovers() {
        router.stop()
        config = config.copy(providers = config.providers.map { it.copy(kind = ProviderKind.CODEX) })
        val entered = CountDownLatch(8)
        val release = CountDownLatch(1)
        router = RouterServer({ config }, codexCredentials = {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            CodexCredentials("", "")
        }).also { it.start(5_000, true) }
        val workers = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val pending = (1..8).map { workers.submit<Int> {
                request("/v1/responses", """{"model":"fast","input":"hello"}""").use { it.code }
            } }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            request("/v1/responses", """{"model":"fast","input":"hello"}""").use { assertEquals(429, it.code) }
            release.countDown()
            pending.forEach { assertEquals(503, it.get(3, TimeUnit.SECONDS)) }
            request("/v1/responses", """{"model":"fast","input":"hello"}""").use { assertEquals(503, it.code) }
        } finally { release.countDown(); workers.shutdownNow() }
    }

    @Test fun codexSseCanBecomeJsonChatThroughCredentialResolver() {
        router.stop()
        config = config.copy(providers = listOf(ProviderConfig("deepseek", "Codex", ProviderKind.CODEX, "unused")))
        val upstreamClient = RouterServer.defaultClient().newBuilder().addInterceptor { chain ->
            assertEquals("Bearer account-token", chain.request().header("Authorization"))
            assertEquals("account-id", chain.request().header("ChatGPT-Account-ID"))
            chain.proceed(chain.request().newBuilder().url(upstream.url("/responses")).build())
        }.build()
        router = RouterServer({ config }, upstreamClient, codexCredentials = { CodexCredentials("account-token", "account-id") }).also { it.start(5_000, true) }
        upstream.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("""
            data: {"type":"response.completed","response":{"id":"resp_test","created_at":123,"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"hello from Codex"}]}],"usage":{"input_tokens":2,"output_tokens":4,"total_tokens":6}}}

        """.trimIndent() + "\n\n"))
        request("/v1/chat/completions", """{"model":"fast","messages":[{"role":"user","content":"hello"}]}""").use {
            assertEquals(200, it.code)
            val result = Json.parseToJsonElement(it.body!!.string()).jsonObject
            assertEquals("fast", result["model"]!!.jsonPrimitive.content)
            assertEquals("hello from Codex", result["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content)
            assertEquals(6, result["usage"]!!.jsonObject["total_tokens"]!!.jsonPrimitive.int)
        }
        val forwarded = Json.parseToJsonElement(upstream.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
        assertEquals(true, forwarded["stream"]!!.jsonPrimitive.boolean)
        assertEquals(false, forwarded["store"]!!.jsonPrimitive.boolean)
        assertTrue(forwarded.containsKey("input")); assertFalse(forwarded.containsKey("messages"))
    }
}
