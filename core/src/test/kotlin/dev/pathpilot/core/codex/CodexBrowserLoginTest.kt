package dev.pathpilot.core.codex

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64

class CodexBrowserLoginTest {
    @Test fun browserAuthorizationUsesOfficialPkceParametersAndRegisteredLoopbackRedirect() {
        val client = CodexOAuthClient(nowEpochSeconds = { 1_000 })
        val challenge = client.beginBrowserLogin()
        val url = challenge.authorizationUrl.toHttpUrl()
        assertEquals("https://auth.openai.com/oauth/authorize", url.newBuilder().query(null).build().toString())
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals(CodexOAuthClient.CLIENT_ID, url.queryParameter("client_id"))
        assertEquals(CodexOAuthClient.DEFAULT_BROWSER_REDIRECT, url.queryParameter("redirect_uri"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("openid profile email offline_access api.connectors.read api.connectors.invoke", url.queryParameter("scope"))
        assertEquals("true", url.queryParameter("id_token_add_organizations"))
        assertEquals("true", url.queryParameter("codex_cli_simplified_flow"))
        assertEquals("codex_cli_rs", url.queryParameter("originator"))
        assertEquals(86, challenge.codeVerifier.length)
        assertEquals(43, challenge.state.length)
        val expectedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(challenge.codeVerifier.toByteArray(Charsets.UTF_8)))
        assertEquals(expectedChallenge, url.queryParameter("code_challenge"))
        assertEquals(1_900L, challenge.expiresAtEpochSeconds)
        assertFalse(challenge.authorizationUrl.contains(challenge.codeVerifier))
        assertFalse(challenge.toString().contains(challenge.state))
        assertFalse(challenge.toString().contains(challenge.codeVerifier))
        val next = client.beginBrowserLogin(CodexOAuthClient.FALLBACK_BROWSER_REDIRECT)
        assertNotEquals(challenge.state, next.state)
        assertNotEquals(challenge.codeVerifier, next.codeVerifier)
        assertEquals(CodexOAuthClient.FALLBACK_BROWSER_REDIRECT, next.redirectUri)
    }

    @Test fun onlyTheTwoRegisteredLocalRedirectsAreAccepted() {
        val client = CodexOAuthClient()
        for (uri in listOf("http://127.0.0.1:1456/auth/callback", "http://localhost:1455/auth/callback",
            "http://192.168.1.2:1455/auth/callback", "https://example.com/auth/callback",
            "http://127.0.0.1:1455/auth/callback?secret=should-not-echo")) {
            val error = assertThrows(CodexAuthException::class.java) { client.beginBrowserLogin(uri) }
            assertEquals(CodexAuthError.INVALID_CONFIGURATION, error.kind)
            assertFalse(error.toString().contains(uri))
        }
    }

    @Test fun mismatchedCallbacksAreIgnoredWithoutConsumingTheValidAttempt() {
        MockWebServer().use { server ->
            val client = CodexOAuthClient(issuer = server.url("/").toString(), nowEpochSeconds = { 1_000 })
            val challenge = client.beginBrowserLogin()
            val valid = callback(challenge)
            val rejected = listOf(
                valid.replace(challenge.state, "wrong-state-secret"),
                valid.replace("127.0.0.1", "example.com"),
                valid.replace(":1455", ":1457"),
                valid.replace("/auth/callback", "/wrong"),
                valid.replace("http://", "https://"),
                valid.replace("127.0.0.1", "secret@127.0.0.1"),
                "$valid#fragment-secret",
                "$valid&state=${challenge.state}",
                valid.replace(challenge.state, challenge.state + ".unknown_suffix"),
                "not-a-url-secret",
            )
            for (url in rejected) {
                assertFalse(challenge.matchesCallback(url))
                val error = assertThrows(CodexAuthException::class.java) { client.completeBrowserLogin(challenge, url) }
                assertEquals(CodexAuthError.STATE_MISMATCH, error.kind)
                assertFalse(error.toString().contains("secret"))
                assertFalse(error.toString().contains(challenge.state))
            }
            assertEquals(0, server.requestCount)
            assertTrue(challenge.matchesCallback(valid))
            server.enqueue(tokens())
            assertEquals("account", client.completeBrowserLogin(challenge, valid).accountId)
            val request = server.takeRequest()
            assertEquals("/oauth/token", request.path)
            assertEquals("application/x-www-form-urlencoded", request.getHeader("Content-Type"))
            val fields = formFields(request.body.readUtf8())
            assertEquals("authorization_code", fields["grant_type"])
            assertEquals(CodexOAuthClient.CLIENT_ID, fields["client_id"])
            assertEquals("one-time/code+secret=", fields["code"])
            assertEquals(challenge.codeVerifier, fields["code_verifier"])
            assertEquals(challenge.redirectUri, fields["redirect_uri"])
            assertFalse(fields.containsKey("client_secret"))
            assertEquals(CodexAuthError.EXPIRED,
                assertThrows(CodexAuthException::class.java) { client.completeBrowserLogin(challenge, valid) }.kind)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun officialOnboardingStateSuffixIsAcceptedButNotArbitraryPrefixes() {
        val client = CodexOAuthClient()
        val challenge = client.beginBrowserLogin()
        assertTrue(challenge.matchesCallback(callback(challenge, challenge.state + ".onboarding_entrypoint=life_sciences")))
        assertFalse(challenge.matchesCallback(callback(challenge, challenge.state + ".onboarding_entrypoint=other")))
        assertFalse(challenge.matchesCallback(callback(challenge, challenge.state + ".onboarding_entrypoint=life_sciences.extra")))
    }

    @Test fun providerErrorIsAcceptedOnlyAfterStateAndNeverEchoesDescription() {
        MockWebServer().use { server ->
            val client = CodexOAuthClient(issuer = server.url("/").toString())
            val challenge = client.beginBrowserLogin()
            val url = challenge.redirectUri.toHttpUrl().newBuilder()
                .addQueryParameter("state", challenge.state)
                .addQueryParameter("error", "access_denied")
                .addQueryParameter("error_description", "secret-callback-description")
                .build().toString()
            val error = assertThrows(CodexAuthException::class.java) { client.completeBrowserLogin(challenge, url) }
            assertEquals(CodexAuthError.ACCESS_DENIED, error.kind)
            assertFalse(error.toString().contains("secret"))
            assertEquals(0, server.requestCount)
            assertEquals(CodexAuthError.EXPIRED,
                assertThrows(CodexAuthException::class.java) { client.completeBrowserLogin(challenge, callback(challenge)) }.kind)
        }
    }

    @Test fun expiryCancellationAndMissingCodeNeverPostTokens() {
        MockWebServer().use { server ->
            var now = 1_000L
            val client = CodexOAuthClient(issuer = server.url("/").toString(), nowEpochSeconds = { now })
            val challenge = client.beginBrowserLogin()
            val noCode = challenge.redirectUri.toHttpUrl().newBuilder().addQueryParameter("state", challenge.state).build().toString()
            assertEquals(CodexAuthError.INVALID_RESPONSE,
                assertThrows(CodexAuthException::class.java) { client.completeBrowserLogin(challenge, noCode) }.kind)
            now = challenge.expiresAtEpochSeconds
            assertEquals(CodexAuthError.EXPIRED,
                assertThrows(CodexAuthException::class.java) { client.completeBrowserLogin(challenge, callback(challenge)) }.kind)
            client.cancel()
            assertEquals(CodexAuthError.CANCELLED,
                assertThrows(CodexAuthException::class.java) { client.completeBrowserLogin(challenge, callback(challenge)) }.kind)
            assertEquals(CodexAuthError.CANCELLED,
                assertThrows(CodexAuthException::class.java) { client.beginBrowserLogin() }.kind)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun browserServerErrorsAreNotMisreportedAsUserDeniedAuthorization() {
        MockWebServer().use { server ->
            val client = CodexOAuthClient(issuer = server.url("/").toString())
            for ((codes, expected) in listOf(listOf("server_error") to CodexAuthError.UPSTREAM,
                listOf("temporarily_unavailable") to CodexAuthError.UPSTREAM,
                listOf("") to CodexAuthError.INVALID_RESPONSE,
                listOf("server_error", "access_denied") to CodexAuthError.INVALID_RESPONSE)) {
                val challenge = client.beginBrowserLogin()
                val url = challenge.redirectUri.toHttpUrl().newBuilder().addQueryParameter("state", challenge.state)
                    .addQueryParameter("error_description", "secret-that-must-not-appear")
                    .apply { codes.forEach { addQueryParameter("error", it) } }.build().toString()
                val error = assertThrows(CodexAuthException::class.java) { client.completeBrowserLogin(challenge, url) }
                assertEquals(expected, error.kind)
                assertFalse(error.toString().contains("secret"))
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun interruptedBrowserCodeExchangeCannotReplayTheSameCode() {
        MockWebServer().use { server ->
            val client = CodexOAuthClient(issuer = server.url("/").toString())
            val challenge = client.beginBrowserLogin()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val error = assertThrows(CodexAuthException::class.java) {
                client.completeBrowserLogin(challenge, callback(challenge))
            }
            assertEquals(CodexAuthError.NETWORK, error.kind)
            assertEquals(CodexAuthStage.CODE_EXCHANGE, error.stage)
            assertFalse(error.canRetryPolling)
            assertEquals(CodexAuthError.EXPIRED,
                assertThrows(CodexAuthException::class.java) { client.completeBrowserLogin(challenge, callback(challenge)) }.kind)
            assertEquals(1, server.requestCount)
        }
    }

    private fun callback(challenge: BrowserLoginChallenge, state: String = challenge.state): String =
        challenge.redirectUri.toHttpUrl().newBuilder().addQueryParameter("state", state)
            .addQueryParameter("code", "one-time/code+secret=").build().toString()

    private fun formFields(value: String): Map<String, String> = value.split('&').associate { part ->
        val pair = part.split('=', limit = 2)
        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
    }

    private fun tokens(): MockResponse {
        val claims = """{"exp":9000,"https://api.openai.com/auth":{"chatgpt_account_id":"account"}}"""
        val jwt = "header.${Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray())}.signature"
        return MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"access_token":"$jwt","id_token":"$jwt","refresh_token":"fixture-refresh-secret"}""")
    }
}
