package dev.pathpilot.core.codex

import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CodexOAuthClientTest {
    @Test fun deviceLoginPollsThenExchangesServerPkceVerifier() {
        MockWebServer().use { server ->
            server.enqueue(json("""{"device_auth_id":"device-id","usercode":"ABCD-EFGH","interval":"5"}"""))
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(json("""{"authorization_code":"auth-code","code_verifier":"pkce-verifier","code_challenge":"challenge"}"""))
            server.enqueue(json(tokensJson("account-a", "refresh-a")))
            val client = CodexOAuthClient(issuer = server.url("/").toString(), nowEpochSeconds = { 1_000 })
            val challenge = client.beginDeviceLogin()
            assertEquals("ABCD-EFGH", challenge.userCode)
            assertEquals(5L, challenge.intervalSeconds)
            assertEquals(1_900L, challenge.expiresAtEpochSeconds)
            assertEquals(server.url("/codex/device").toString(), challenge.verificationUrl)
            assertEquals(DeviceLoginResult.Pending, client.pollDeviceLogin(challenge))
            val authorized = client.pollDeviceLogin(challenge) as DeviceLoginResult.Authorized
            assertEquals("account-a", authorized.tokens.accountId)
            assertEquals("refresh-a", authorized.tokens.refreshToken)
            assertFalse(authorized.tokens.needsRefresh(1_000))
            val start = server.takeRequest()
            assertEquals("/api/accounts/deviceauth/usercode", start.path)
            assertEquals(CodexOAuthClient.CLIENT_ID, Json.parseToJsonElement(start.body.readUtf8()).jsonObject["client_id"]!!.jsonPrimitive.content)
            val poll = server.takeRequest()
            assertEquals("device-id", Json.parseToJsonElement(poll.body.readUtf8()).jsonObject["device_auth_id"]!!.jsonPrimitive.content)
            server.takeRequest()
            val exchange = server.takeRequest()
            assertEquals("/oauth/token", exchange.path)
            assertEquals("application/x-www-form-urlencoded", exchange.getHeader("Content-Type"))
            val form = exchange.body.readUtf8()
            assertTrue(form.contains("grant_type=authorization_code"))
            assertTrue(form.contains("code_verifier=pkce-verifier"))
            assertTrue(form.contains("code=auth-code"))
        }
    }

    @Test fun refreshUsesJsonAndPreservesOmittedRefreshAndIdentityTokens() {
        MockWebServer().use { server ->
            val original = CodexTokens("old", "keep-refresh", "keep-id", "account-a", 1_000)
            val access = jwt("account-a", 9_000)
            server.enqueue(json("""{"access_token":"$access"}"""))
            val client = CodexOAuthClient(issuer = server.url("/").toString(), nowEpochSeconds = { 2_000 })
            val refreshed = client.refresh(original)
            assertEquals("keep-refresh", refreshed.refreshToken)
            assertEquals("keep-id", refreshed.idToken)
            assertEquals(9_000L, refreshed.expiresAtEpochSeconds)
            val request = server.takeRequest()
            assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("keep-refresh", body["refresh_token"]!!.jsonPrimitive.content)
            assertEquals("refresh_token", body["grant_type"]!!.jsonPrimitive.content)
        }
    }

    @Test fun refreshRejectsAccountChangesAndNeverPrintsCredentials() {
        MockWebServer().use { server ->
            server.enqueue(json(tokensJson("account-b", "secret-new-refresh")))
            val old = CodexTokens("secret-access", "secret-refresh", jwt("account-a", 8_000), "account-a", 1_000)
            val client = CodexOAuthClient(issuer = server.url("/").toString())
            val failure = assertThrows(CodexAuthException::class.java) { client.refresh(old) }
            assertEquals(CodexAuthError.UNAUTHORIZED, failure.kind)
            assertEquals(CodexAuthStage.REFRESH, failure.stage)
            assertFalse(old.toString().contains("secret"))
        }
    }

    @Test fun refreshCannotSucceedWithoutANewAccessToken() {
        MockWebServer().use { server ->
            val old = CodexTokens("previous-access-secret", "previous-refresh-secret", "", "account-a", 1_000)
            val client = CodexOAuthClient(issuer = server.url("/").toString())
            listOf("{}", """{"access_token":""}""", """{"access_token":123}""", """{"refresh_token":"replacement-refresh-secret"}""").forEach { body ->
                server.enqueue(json(body))
                val error = assertThrows(CodexAuthException::class.java) { client.refresh(old) }
                assertEquals(CodexAuthError.INVALID_RESPONSE, error.kind)
                assertFalse(error.toString().contains("secret"))
            }
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun errorBodiesAreNotExposedAnd401IsNotPending() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("echoed-secret-refresh"))
            val client = CodexOAuthClient(issuer = server.url("/").toString(), nowEpochSeconds = { 1_000 })
            val challenge = DeviceLoginChallenge("https://auth.openai.com/codex/device", "code", "device", 5, 2_000)
            val error = assertThrows(CodexAuthException::class.java) { client.pollDeviceLogin(challenge) }
            assertEquals(401, error.httpStatus)
            assertFalse(error.message!!.contains("echoed-secret"))
        }
    }

    @Test fun expiredChallengesDoNotSendRequests() {
        MockWebServer().use { server ->
            val client = CodexOAuthClient(issuer = server.url("/").toString(), nowEpochSeconds = { 2_000 })
            val challenge = DeviceLoginChallenge("https://auth.openai.com/codex/device", "code", "device", 5, 1_999)
            assertThrows(CodexAuthException::class.java) { client.pollDeviceLogin(challenge) }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun transientPollFailuresKeepTheSameChallengeRetryableUntilItExpires() {
        MockWebServer().use { server ->
            val client = CodexOAuthClient(issuer = server.url("/").toString(), nowEpochSeconds = { 1_000 })
            val challenge = DeviceLoginChallenge("https://auth.openai.com/codex/device", "code", "device", 5, 2_000)
            for (response in listOf(MockResponse().setResponseCode(429).setHeader("Retry-After", "17"),
                MockResponse().setResponseCode(503), MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))) {
                server.enqueue(response.setBody("secret-from-upstream"))
                val error = assertThrows(CodexAuthException::class.java) { client.pollDeviceLogin(challenge) }
                assertTrue(error.canRetryPolling)
                assertEquals(CodexAuthStage.DEVICE_POLL, error.stage)
                assertFalse(error.toString().contains("secret"))
                if (error.httpStatus == 429) assertEquals(17L, error.retryAfterSeconds)
            }
            server.enqueue(MockResponse().setResponseCode(404))
            assertEquals(DeviceLoginResult.Pending, client.pollDeviceLogin(challenge))
            assertEquals(4, server.requestCount)
            repeat(4) { assertEquals("/api/accounts/deviceauth/token", server.takeRequest().path) }
        }
    }

    @Test fun aFailedOneTimeCodeExchangeCannotBeRetriedAsAPendingPoll() {
        MockWebServer().use { server ->
            val client = CodexOAuthClient(issuer = server.url("/").toString(), nowEpochSeconds = { 1_000 })
            val challenge = DeviceLoginChallenge("https://auth.openai.com/codex/device", "code", "device", 5, 2_000)
            server.enqueue(json("""{"authorization_code":"one-time-secret","code_verifier":"verifier-secret"}"""))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val error = assertThrows(CodexAuthException::class.java) { client.pollDeviceLogin(challenge) }
            assertEquals(CodexAuthStage.CODE_EXCHANGE, error.stage)
            assertEquals(CodexAuthError.NETWORK, error.kind)
            assertFalse(error.canRetryPolling)
            assertEquals(CodexAuthError.EXPIRED,
                assertThrows(CodexAuthException::class.java) { client.pollDeviceLogin(challenge) }.kind)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun cancellingAnActivePollAlsoPreventsItsCodeExchangeAndQueuedRequests() {
        MockWebServer().use { server ->
            val client = CodexOAuthClient(issuer = server.url("/").toString(), nowEpochSeconds = { 1_000 })
            val challenge = DeviceLoginChallenge("https://auth.openai.com/codex/device", "code", "device", 5, 2_000)
            server.enqueue(json("""{"authorization_code":"code-secret","code_verifier":"verifier-secret"}""")
                .setBodyDelay(2, TimeUnit.SECONDS))
            val workers = Executors.newSingleThreadExecutor()
            try {
                val pending = workers.submit<CodexAuthException> {
                    assertThrows(CodexAuthException::class.java) { client.pollDeviceLogin(challenge) }
                }
                assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                client.cancel()
                val failure = pending.get(1, TimeUnit.SECONDS)
                assertEquals(CodexAuthError.CANCELLED, failure.kind)
                assertFalse(failure.canRetryPolling)
                assertEquals(CodexAuthError.CANCELLED,
                    assertThrows(CodexAuthException::class.java) { client.beginDeviceLogin() }.kind)
                assertEquals(1, server.requestCount)
            } finally { workers.shutdownNow() }
        }
    }

    @Test fun timeoutIsRetryableOnlyDuringPollingAndDistinctFromCancellation() {
        MockWebServer().use { server ->
            val http = OkHttpClient.Builder().callTimeout(100, TimeUnit.MILLISECONDS).build()
            val client = CodexOAuthClient(http, issuer = server.url("/").toString(), nowEpochSeconds = { 1_000 })
            val challenge = DeviceLoginChallenge("https://auth.openai.com/codex/device", "code", "device", 5, 2_000)
            server.enqueue(MockResponse().setResponseCode(403).setHeadersDelay(500, TimeUnit.MILLISECONDS))
            val error = assertThrows(CodexAuthException::class.java) { client.pollDeviceLogin(challenge) }
            assertEquals(CodexAuthError.TIMEOUT, error.kind)
            assertTrue(error.canRetryPolling)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun unavailableDeviceLoginAndInvalidRefreshGrantHaveDistinctRedactedErrors() {
        MockWebServer().use { server ->
            val client = CodexOAuthClient(issuer = server.url("/").toString())
            server.enqueue(MockResponse().setResponseCode(404).setBody("secret"))
            assertEquals(CodexAuthError.DEVICE_LOGIN_UNAVAILABLE,
                assertThrows(CodexAuthException::class.java) { client.beginDeviceLogin() }.kind)
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant","error_description":"secret-refresh"}"""))
            val old = CodexTokens("access-secret", "refresh-secret", "", "account", 1_000)
            val error = assertThrows(CodexAuthException::class.java) { client.refresh(old) }
            assertEquals(400, error.httpStatus)
            assertEquals(CodexAuthError.UNAUTHORIZED, error.kind)
            assertFalse(error.canRetryPolling)
            assertFalse(error.toString().contains("secret"))
        }
    }

    @Test fun intervalParsingRejectsMalformedValuesAndBoundsWaitByDeviceExpiry() {
        MockWebServer().use { server ->
            val client = CodexOAuthClient(issuer = server.url("/").toString())
            for (interval in listOf("{}", "[]", "-1", "true", "\"invalid\"")) {
                server.enqueue(json("""{"device_auth_id":"device","user_code":"code","interval":$interval}"""))
                assertEquals(CodexAuthError.INVALID_RESPONSE,
                    assertThrows(CodexAuthException::class.java) { client.beginDeviceLogin() }.kind)
            }
            server.enqueue(json("""{"device_auth_id":"device","user_code":"code","interval":" 5 "}"""))
            assertEquals(5L, client.beginDeviceLogin().intervalSeconds)
            server.enqueue(json("""{"device_auth_id":"device","user_code":"code","interval":9223372036854775807}"""))
            assertEquals(900L, client.beginDeviceLogin().intervalSeconds)
        }
    }

    @Test fun authenticationRedirectIsNotFollowedAndSecretsAreNotIncludedInDiagnostics() {
        MockWebServer().use { server ->
            val client = CodexOAuthClient(issuer = server.url("/").toString())
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/elsewhere?token=secret")))
            val error = assertThrows(CodexAuthException::class.java) { client.beginDeviceLogin() }
            assertEquals(CodexAuthError.UPSTREAM, error.kind)
            assertFalse(error.toString().contains("secret"))
            assertEquals(1, server.requestCount)
        }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun tokensJson(account: String, refresh: String): String =
        """{"access_token":"${jwt(account, 8_000)}","id_token":"${jwt(account, 8_000)}","refresh_token":"$refresh"}"""

    private fun jwt(account: String, expiry: Long): String {
        val claims = """{"exp":$expiry,"https://api.openai.com/auth":{"chatgpt_account_id":"$account"}}"""
        return "eyJhbGciOiJub25lIn0.${Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray())}.test"
    }
}
