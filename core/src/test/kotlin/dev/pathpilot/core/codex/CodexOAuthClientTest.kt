package dev.pathpilot.core.codex

import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

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
            assertTrue(failure.message!!.contains("account changed"))
            assertFalse(old.toString().contains("secret"))
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

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun tokensJson(account: String, refresh: String): String =
        """{"access_token":"${jwt(account, 8_000)}","id_token":"${jwt(account, 8_000)}","refresh_token":"$refresh"}"""

    private fun jwt(account: String, expiry: Long): String {
        val claims = """{"exp":$expiry,"https://api.openai.com/auth":{"chatgpt_account_id":"$account"}}"""
        return "eyJhbGciOiJub25lIn0.${Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray())}.test"
    }
}
