package dev.pathpilot.core.network

import dev.pathpilot.core.*
import dev.pathpilot.core.codex.*
import dev.pathpilot.core.providers.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class OutboundProxyTest {
    @Test fun oldConfigurationDefaultsToSystemAndCustomProxyRoundTrips() {
        val old = Json.decodeFromString<RouterConfig>("""{"port":8317,"accessKey":"old-local-key","providers":[],"routes":[]}""")
        assertEquals(OutboundProxyConfig(), old.outboundProxy)
        val updated = old.copy(outboundProxy = OutboundProxyConfig(OutboundProxyMode.SOCKS5, "127.0.0.1", 1080))
        assertEquals(updated, Json.decodeFromString<RouterConfig>(Json.encodeToString(updated)))
    }

    @Test fun proxyHostAndPortValidationDoesNotPerformDnsOrAcceptUrls() {
        listOf("127.0.0.1", "proxy.example", "localhost", "::1", "[::1]").forEach { host ->
            assertTrue(OutboundProxyValidation.validate(OutboundProxyConfig(OutboundProxyMode.HTTP, host, 7890)).isValid)
        }
        listOf("", "https://proxy.example", "proxy.example:7890", "user:password@proxy", "proxy/path", "proxy?secret", "bad host", "proxy\nhost").forEach { host ->
            assertNotNull(OutboundProxyValidation.validate(OutboundProxyConfig(OutboundProxyMode.HTTP, host, 7890)).hostError)
        }
        assertNotNull(OutboundProxyValidation.validate(OutboundProxyConfig(OutboundProxyMode.SOCKS5, "localhost", 0)).portError)
        assertNotNull(OutboundProxyValidation.validate(OutboundProxyConfig(OutboundProxyMode.HTTP, "localhost", 65536)).portError)
        assertEquals("::1", OutboundProxyValidation.normalized(OutboundProxyConfig(OutboundProxyMode.SOCKS5, " [::1] ", 1080)).host)
        assertTrue(OutboundProxyValidation.validate(OutboundProxyConfig(OutboundProxyMode.SYSTEM, "", 0)).isValid)
    }

    @Test fun httpAndSocksProxiesCarryModelDiscoveryOAuthAndBothRouterProviders() {
        for (mode in listOf(OutboundProxyMode.HTTP, OutboundProxyMode.SOCKS5)) {
            TlsOrigin().use { origin ->
                origin.proxy(mode).use { proxy ->
                    val proxyConfig = OutboundProxyConfig(mode, "127.0.0.1", proxy.port)
                    val probe = ProviderModelsClient(origin.client, proxyProvider = { proxyConfig })
                    origin.server.enqueue(json("""{"data":[{"id":"local-model"}]}"""))
                    assertEquals(listOf("local-model"), probe.probe("https://provider.invalid/v1", "provider-secret").models)
                    origin.server.takeRequest().let { request ->
                        assertEquals("/v1/models", request.path)
                        assertEquals("Bearer provider-secret", request.getHeader("Authorization"))
                    }

                    val oauth = CodexOAuthClient(origin.client, issuer = "https://auth-provider.invalid",
                        nowEpochSeconds = { 1_000 }, proxyProvider = { proxyConfig })
                    origin.server.enqueue(json("""{"device_auth_id":"device","usercode":"CODE","interval":"5"}"""))
                    val challenge = oauth.beginDeviceLogin()
                    assertEquals("/api/accounts/deviceauth/usercode", origin.server.takeRequest().path)
                    origin.server.enqueue(json("""{"authorization_code":"code","code_verifier":"verifier"}"""))
                    origin.server.enqueue(json("""{"access_token":"${jwt()}","refresh_token":"refresh-secret"}"""))
                    val authorized = oauth.pollDeviceLogin(challenge) as DeviceLoginResult.Authorized
                    assertEquals("account-a", authorized.tokens.accountId)
                    assertEquals("/api/accounts/deviceauth/token", origin.server.takeRequest().path)
                    assertEquals("/oauth/token", origin.server.takeRequest().path)
                    origin.server.enqueue(json("""{"access_token":"${jwt()}","refresh_token":"rotated-refresh"}"""))
                    assertEquals("rotated-refresh", oauth.refresh(authorized.tokens).refreshToken)
                    origin.server.takeRequest().let { request ->
                        assertEquals("/oauth/token", request.path)
                        assertTrue(request.body.readUtf8().contains("refresh-secret"))
                    }

                    val config = AtomicReference(RouterConfig(0, "local-key", listOf(
                        ProviderConfig("custom", "Custom", ProviderKind.OPENAI_COMPATIBLE, "https://provider.invalid/v1", "provider-secret"),
                        ProviderConfig("codex", "Codex", ProviderKind.CODEX, CodexBackend.BASE_URL, "codex-access", codexAccountId = "account-a"),
                    ), listOf(RouteConfig("custom-model", "custom", "upstream-model"), RouteConfig("code", "codex", "codex-model")), proxyConfig))
                    val router = RouterServer(config::get, origin.client)
                    router.start(5_000, true)
                    try {
                        origin.server.enqueue(json("""{"choices":[{"message":{"content":"through-proxy"}}]}"""))
                        lanRequest(router, "/v1/chat/completions", """{"model":"custom-model","messages":[{"role":"user","content":"hi"}]}""").use {
                            assertEquals(200, it.code); assertTrue(it.body!!.string().contains("through-proxy"))
                        }
                        assertEquals("/v1/chat/completions", origin.server.takeRequest().path)
                        origin.server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                            "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_proxy\",\"status\":\"completed\",\"output\":[]}}\n\n"))
                        lanRequest(router, "/v1/responses", """{"model":"code","input":"hello"}""").use {
                            assertEquals(200, it.code); assertTrue(it.body!!.string().contains("resp_proxy"))
                        }
                        origin.server.takeRequest().let {
                            assertEquals("/backend-api/codex/responses", it.path)
                            assertEquals("Bearer codex-access", it.getHeader("Authorization"))
                            assertEquals("account-a", it.getHeader("ChatGPT-Account-ID"))
                        }
                    } finally { router.stop() }
                    val destinations = proxy.requests.toList()
                    assertTrue(destinations.any { it.host == "provider.invalid" })
                    assertTrue(destinations.any { it.host == "auth-provider.invalid" })
                    assertTrue(destinations.any { it.host == "chatgpt.com" })
                    if (mode == OutboundProxyMode.SOCKS5) assertTrue(destinations.all { it.remoteDns })
                    assertTrue("Local proxy handshake must succeed", proxy.failures.isEmpty())
                }
            }
        }
    }

    @Test fun systemModeUsesPlatformSelectorAndKeepsNormalSocketFactory() {
        TlsOrigin().use { origin ->
            origin.proxy(OutboundProxyMode.HTTP).use { proxy ->
                var selections = 0
                val selector = object : ProxySelector() {
                    override fun select(uri: URI): List<Proxy> { selections++; return listOf(Proxy(Proxy.Type.HTTP, proxy.address)) }
                    override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
                }
                val factory = OutboundHttpClientFactory(origin.client, systemProxySelector = selector)
                origin.server.enqueue(json("{\"ok\":true}"))
                val transport = factory.client()
                assertNull(transport.proxy)
                assertSame(selector, transport.proxySelector)
                assertSame(origin.client.socketFactory, transport.socketFactory)
                transport.newCall(Request.Builder().url("https://provider.invalid/status").build()).execute().use {
                    assertEquals(200, it.code); it.body!!.string()
                }
                assertTrue(selections > 0)
                assertEquals("provider.invalid", proxy.requests.poll(1, TimeUnit.SECONDS)!!.host)
            }
        }
    }

    @Test fun changingConfigMovesNewRequestsToNewProxyWithoutRestart() {
        TlsOrigin().use { origin ->
            origin.proxy(OutboundProxyMode.HTTP).use { first ->
                origin.proxy(OutboundProxyMode.SOCKS5).use { second ->
                    val currentProxy = AtomicReference(OutboundProxyConfig(OutboundProxyMode.HTTP, "127.0.0.1", first.port))
                    val probe = ProviderModelsClient(origin.client, proxyProvider = currentProxy::get)
                    origin.server.enqueue(json("""{"data":[{"id":"first"}]}"""))
                    assertEquals(listOf("first"), probe.probe("https://provider.invalid/v1", "key").models)
                    currentProxy.set(OutboundProxyConfig(OutboundProxyMode.SOCKS5, "127.0.0.1", second.port))
                    origin.server.enqueue(json("""{"data":[{"id":"second"}]}"""))
                    assertEquals(listOf("second"), probe.probe("https://provider.invalid/v1", "key").models)
                    assertEquals("provider.invalid", first.requests.poll(1, TimeUnit.SECONDS)!!.host)
                    assertEquals("provider.invalid", second.requests.poll(1, TimeUnit.SECONDS)!!.host)
                }
            }
        }
    }

    @Test fun failedExplicitProxyDoesNotSilentlyFallBackToOrigin() {
        MockWebServer().use { origin ->
            val baseUrl = origin.url("/v1").toString()
            val unavailablePort = ServerSocket(0).use { it.localPort }
            val fastClient = OkHttpClient.Builder().callTimeout(500, TimeUnit.MILLISECONDS).build()
            for (mode in listOf(OutboundProxyMode.HTTP, OutboundProxyMode.SOCKS5)) {
                val probe = ProviderModelsClient(fastClient, proxyProvider = { OutboundProxyConfig(mode, "127.0.0.1", unavailablePort) })
                val error = assertThrows(ProviderProbeException::class.java) { probe.probe(baseUrl, "secret") }
                assertEquals(ProviderProbeError.NETWORK, error.kind)
                assertFalse(error.message!!.contains("secret"))
            }
            assertEquals(0, origin.requestCount)
        }
    }

    private class TlsOrigin : java.io.Closeable {
        val certificate = HeldCertificate.Builder().commonName("PathPilot local proxy integration test")
            .addSubjectAlternativeName("provider.invalid").addSubjectAlternativeName("auth-provider.invalid")
            .addSubjectAlternativeName("chatgpt.com").build()
        private val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        private val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply { useHttps(serverCertificates.sslSocketFactory(), false); start() }
        val client = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .dns(object : Dns {
                override fun lookup(hostname: String) = if (hostname == "127.0.0.1" || hostname == "localhost") Dns.SYSTEM.lookup(hostname)
                else throw UnknownHostException("Destination DNS must be resolved by the selected proxy")
            })
            .callTimeout(5, TimeUnit.SECONDS).build()
        fun proxy(mode: OutboundProxyMode) = LocalTunnelProxy(
            if (mode == OutboundProxyMode.HTTP) LocalTunnelProxy.Mode.HTTP_CONNECT else LocalTunnelProxy.Mode.SOCKS5,
            InetSocketAddress("127.0.0.1", server.port),
        )
        override fun close() { client.connectionPool.evictAll(); server.shutdown() }
    }

    private fun lanRequest(router: RouterServer, path: String, body: String) =
        OkHttpClient.Builder().proxy(Proxy.NO_PROXY).readTimeout(5, TimeUnit.SECONDS).build().newCall(Request.Builder()
            .url("http://127.0.0.1:${router.listeningPort}$path")
            .header("Authorization", "Bearer local-key")
            .post(body.toRequestBody("application/json".toMediaType())).build()).execute()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun jwt(): String {
        val claims = """{"exp":9000,"https://api.openai.com/auth":{"chatgpt_account_id":"account-a"}}"""
        return "eyJhbGciOiJub25lIn0.${Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray())}.test"
    }
}
