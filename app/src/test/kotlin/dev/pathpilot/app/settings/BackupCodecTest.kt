package dev.pathpilot.app.settings

import dev.pathpilot.core.ProviderConfig
import dev.pathpilot.core.ProviderKind
import dev.pathpilot.core.RouteConfig
import dev.pathpilot.core.RouterConfig
import dev.pathpilot.core.codex.CodexBackend
import dev.pathpilot.core.codex.CodexTokens
import dev.pathpilot.core.mcp.McpConnection
import dev.pathpilot.core.mcp.McpSettings
import dev.pathpilot.core.network.OutboundProxyConfig
import dev.pathpilot.core.network.OutboundProxyMode
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {
    private val password = "test backup passphrase".toCharArray()
    private val fixture = BackupSnapshot(
        createdAtEpochMs = 1_795_600_000_000,
        config = RouterConfig(port = 8317, accessKey = "sk-model-fixture-access-key-123456",
            providers = listOf(
                ProviderConfig("deepseek", "DeepSeek", ProviderKind.DEEPSEEK, "https://api.deepseek.com/v1", "sk-provider-fixture-secret"),
                ProviderConfig("codex", "Codex", ProviderKind.CODEX, CodexBackend.BASE_URL),
            ), routes = listOf(RouteConfig("daily", "deepseek", "deepseek-chat")),
            outboundProxy = OutboundProxyConfig(OutboundProxyMode.HTTP, "127.0.0.1", 7890)),
        preferences = AppPreferences(ThemeMode.DARK, dynamicColor = false, language = AppLanguage.EN),
        mcp = McpSettings(port = 8318, accessKey = "sk-mcp-fixture-access-key-123456", connections = listOf(
            McpConnection(id = "fixture-tools", name = "Tools", url = "https://tools.example.test/mcp", bearerToken = "mcp-fixture-secret",
                allowedTools = setOf("lookup", "search")),
        )),
        codexTokens = mapOf("codex" to CodexTokens("fixture-access-token", "fixture-refresh-token", accountId = "fixture-account", expiresAtEpochSeconds = 2_000_000_000)),
    )

    @Test fun encryptedBackupRoundTripsConfigurationPreferencesMcpAndIncludedSessions() {
        val bytes = BackupCodec.encode(fixture, password)
        assertEquals(fixture, BackupCodec.decode(bytes, password))
        val serialized = bytes.toString(Charsets.UTF_8)
        listOf("sk-provider-fixture-secret", "mcp-fixture-secret", "fixture-refresh-token", "fixture-account")
            .forEach { assertFalse("Secrets are inside the authenticated encrypted payload", serialized.contains(it)) }
        assertTrue(BackupCodec.decode(bytes, password).includesCodexSessions)
        assertNotEquals(serialized, BackupCodec.encode(fixture, password).toString(Charsets.UTF_8))
    }

    @Test fun wrongPasswordAndModifiedPayloadFailAuthentication() {
        val bytes = BackupCodec.encode(fixture, password)
        assertEquals(BackupError.WRONG_PASSWORD_OR_DAMAGED_FILE,
            assertThrows(BackupException::class.java) { BackupCodec.decode(bytes, "another password".toCharArray()) }.reason)
        val envelope = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val ciphertext = Base64.getDecoder().decode(envelope.getValue("ciphertext").jsonPrimitive.content)
        ciphertext[0] = (ciphertext[0].toInt() xor 1).toByte()
        val tampered = JsonObject(envelope + ("ciphertext" to JsonPrimitive(Base64.getEncoder().encodeToString(ciphertext))))
        assertEquals(BackupError.WRONG_PASSWORD_OR_DAMAGED_FILE,
            assertThrows(BackupException::class.java) { BackupCodec.decode(tampered.toString().toByteArray(), password) }.reason)
    }

    @Test fun omittedSessionsRemainDifferentFromAnIncludedEmptySessionSet() {
        val withoutSessions = fixture.copy(codexTokens = null)
        val restored = BackupCodec.decode(BackupCodec.encode(withoutSessions, password), password)
        assertNull(restored.codexTokens)
        assertFalse(restored.includesCodexSessions)
        val explicitEmpty = fixture.copy(codexTokens = emptyMap())
        val restoredEmpty = BackupCodec.decode(BackupCodec.encode(explicitEmpty, password), password)
        assertEquals(emptyMap<String, CodexTokens>(), restoredEmpty.codexTokens)
        assertTrue(restoredEmpty.includesCodexSessions)
    }

    @Test fun unsupportedEnvelopeVersionAndKdfWorkFactorAreRejectedBeforeDecryption() {
        val envelope = Json.parseToJsonElement(BackupCodec.encode(fixture, password).toString(Charsets.UTF_8)).jsonObject
        for ((field, value) in listOf("version" to 2, "iterations" to Int.MAX_VALUE)) {
            val changed = JsonObject(envelope + (field to JsonPrimitive(value))).toString().toByteArray()
            assertEquals(BackupError.UNSUPPORTED_VERSION,
                assertThrows(BackupException::class.java) { BackupCodec.decode(changed, password) }.reason)
        }
    }

    @Test fun malformedFileEmptyPasswordAndOversizedImportHaveSeparateErrors() {
        assertEquals(BackupError.INVALID_FORMAT,
            assertThrows(BackupException::class.java) { BackupCodec.decode("not a backup".toByteArray(), password) }.reason)
        assertEquals(BackupError.PASSWORD_REQUIRED,
            assertThrows(BackupException::class.java) { BackupCodec.encode(fixture, charArrayOf()) }.reason)
        assertEquals(BackupError.FILE_TOO_LARGE,
            assertThrows(BackupException::class.java) { BackupCodec.decode(ByteArray(BackupCodec.MAX_FILE_BYTES + 1), password) }.reason)
    }

    @Test fun importedConfigurationRejectsBrokenRoutesDuplicateIdsAndPortCollisions() {
        val brokenRoute = fixture.copy(config = fixture.config.copy(routes = listOf(RouteConfig("daily", "missing-provider", "model"))))
        assertThrows(IllegalArgumentException::class.java) { BackupValidation.normalized(brokenRoute) }
        val duplicateProvider = fixture.copy(config = fixture.config.copy(providers = fixture.config.providers + fixture.config.providers.first()))
        assertThrows(IllegalArgumentException::class.java) { BackupValidation.normalized(duplicateProvider) }
        val longAlias = fixture.copy(config = fixture.config.copy(routes = listOf(RouteConfig("a".repeat(129), "deepseek", "deepseek-chat"))))
        assertThrows(IllegalArgumentException::class.java) { BackupValidation.normalized(longAlias) }
        val collision = fixture.copy(mcp = fixture.mcp.copy(port = fixture.config.port))
        assertThrows(IllegalArgumentException::class.java) { BackupValidation.normalized(collision) }
    }

    @Test fun importedProviderAndSessionReferencesAreValidatedTogether() {
        val invalidUrl = fixture.copy(config = fixture.config.copy(providers = fixture.config.providers.map {
            if (it.id == "deepseek") it.copy(baseUrl = "file:///tmp/provider") else it
        }))
        assertThrows(IllegalArgumentException::class.java) { BackupValidation.normalized(invalidUrl) }
        val orphanSession = fixture.copy(codexTokens = mapOf("missing" to fixture.codexTokens!!.getValue("codex")))
        assertThrows(IllegalArgumentException::class.java) { BackupValidation.normalized(orphanSession) }
        assertEquals(BackupError.UNSUPPORTED_VERSION,
            assertThrows(BackupException::class.java) { BackupValidation.normalized(fixture.copy(schemaVersion = 2)) }.reason)
    }
}
