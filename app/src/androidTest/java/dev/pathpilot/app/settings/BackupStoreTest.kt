package dev.pathpilot.app.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pathpilot.app.runtime.AppStore
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
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses Android Keystore and real AtomicFile persistence in isolated capture-package directories. */
@RunWith(AndroidJUnit4::class)
class BackupStoreTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private val stores = mutableListOf<AppStore>()
    private val json = Json { encodeDefaults = true }

    @Before fun prepare() {
        check(target.packageName.endsWith(".capture"))
        directory = File(target.noBackupFilesDir, "backup-test-${UUID.randomUUID()}")
        check(directory.mkdirs())
    }

    @After fun clean() {
        stores.forEach { it.requestHistory.close() }
        directory.deleteRecursively()
    }

    @Test fun encryptedBackupRestoresProvidersPreferencesMcpAndOptionalSessionsAcrossReopen() {
        val source = open("source")
        source.restoreBackup(snapshot(includeSession = true), restoreConnectionSettings = true)
        val encoded = source.exportBackup("offline backup password".toCharArray(), includeCodexSessions = true)
        assertFalse(encoded.toString(Charsets.UTF_8).contains("offline-refresh"))
        val decoded = BackupCodec.decode(encoded, "offline backup password".toCharArray())
        assertTrue(decoded.includesCodexSessions)
        assertEquals(2, decoded.config.providers.size)
        assertEquals(1, decoded.config.routes.size)
        assertEquals(1, decoded.mcp.connections.size)

        val restored = open("target")
        restored.restoreBackup(decoded, restoreConnectionSettings = true)
        assertNull(restored.storageError.value)
        assertEquals(decoded.config, restored.config.value)
        assertEquals(decoded.preferences, restored.preferences.value)
        assertEquals(decoded.mcp, restored.mcp.value)
        assertEquals(setOf("offline-codex"), restored.authenticatedCodexProviders.value)
        restored.requestHistory.close()

        val reopened = open("target")
        assertEquals(decoded.config, reopened.config.value)
        assertEquals(decoded.preferences, reopened.preferences.value)
        assertEquals(decoded.mcp, reopened.mcp.value)
        val provider = reopened.config.value.providers.single { it.kind == ProviderKind.CODEX }
        assertEquals("offline-access", reopened.resolveCodexCredentials(provider).accessToken)
    }

    @Test fun defaultRestoreKeepsLocalConnectionSettingsAndUnexportedMatchingLogin() {
        val store = open("preserved")
        val original = snapshot(includeSession = true)
        store.restoreBackup(original, restoreConnectionSettings = true)
        val incoming = original.copy(
            config = original.config.copy(port = 18317, accessKey = key('x'),
                outboundProxy = OutboundProxyConfig(OutboundProxyMode.HTTP, "127.0.0.1", 18888),
                providers = original.config.providers.map { it.copy(name = "Imported ${it.name}") }),
            mcp = original.mcp.copy(port = 18318, accessKey = key('y')),
            preferences = AppPreferences(ThemeMode.LIGHT, true, AppLanguage.ZH_CN),
            codexTokens = null,
        )
        store.restoreBackup(incoming)
        assertEquals(original.config.port, store.config.value.port)
        assertEquals(original.config.accessKey, store.config.value.accessKey)
        assertEquals(original.config.outboundProxy, store.config.value.outboundProxy)
        assertEquals(original.mcp.port, store.mcp.value.port)
        assertEquals(original.mcp.accessKey, store.mcp.value.accessKey)
        assertEquals(incoming.config.providers, store.config.value.providers)
        assertEquals(incoming.preferences, store.preferences.value)
        assertEquals(setOf("offline-codex"), store.authenticatedCodexProviders.value)

        val omitted = BackupCodec.decode(store.exportBackup("password".toCharArray()), "password".toCharArray())
        assertFalse(omitted.includesCodexSessions)
        assertNull(omitted.codexTokens)
        store.restoreBackup(incoming.copy(codexTokens = emptyMap()))
        assertTrue(store.authenticatedCodexProviders.value.isEmpty())
    }

    @Test fun failedAtomicRestoreKeepsPublishedStateAndDurableConfiguration() {
        val store = open("failed")
        val original = snapshot(includeSession = true)
        store.restoreBackup(original, restoreConnectionSettings = true)
        val current = store.config.value
        val preferences = store.preferences.value
        val mcp = store.mcp.value
        val file = File(directory, "failed/pathpilot-config.enc")
        val previousBytes = file.readBytes()
        val blockedNewFile = File(file.path + ".new")
        check(blockedNewFile.mkdir())
        try {
            assertThrows(IOException::class.java) {
                store.restoreBackup(original.copy(preferences = preferences.copy(themeMode = ThemeMode.LIGHT)),
                    restoreConnectionSettings = true)
            }
            assertEquals(current, store.config.value)
            assertEquals(preferences, store.preferences.value)
            assertEquals(mcp, store.mcp.value)
            assertArrayEquals(previousBytes, file.readBytes())
        } finally { blockedNewFile.deleteRecursively() }
        store.restoreBackup(original.copy(preferences = preferences.copy(themeMode = ThemeMode.LIGHT)),
            restoreConnectionSettings = true)
        assertEquals(ThemeMode.LIGHT, open("failed").preferences.value.themeMode)
    }

    @Test fun aValidBackupCanReplaceAnUnreadableConfiguration() {
        val file = File(directory, "corrupt/pathpilot-config.enc")
        check(file.parentFile!!.mkdirs())
        file.writeText("not an encrypted configuration")
        val store = open("corrupt")
        assertNotNull(store.storageError.value)
        val incoming = snapshot(includeSession = false)
        store.restoreBackup(incoming, restoreConnectionSettings = true)
        assertNull(store.storageError.value)
        assertEquals(incoming.config, store.config.value)
        assertEquals(incoming.preferences, store.preferences.value)
        val reopened = open("corrupt")
        assertNull(reopened.storageError.value)
        assertEquals(incoming.config, reopened.config.value)
        assertEquals(incoming.mcp, reopened.mcp.value)
    }

    @Test fun oldEncryptedConfigurationMigratesWithoutRotatingTheNewMcpKeyOnEveryOpen() {
        val fixture = snapshot(includeSession = true)
        val original = fixture.copy(config = fixture.config.copy(port = 8318))
        val store = open("legacy") // Creates the application Keystore key used by the old format.
        store.requestHistory.close()
        val oldJson = buildJsonObject {
            put("config", json.encodeToJsonElement(RouterConfig.serializer(), original.config))
            put("codexTokens", buildJsonObject {
                put("offline-codex", json.encodeToJsonElement(CodexTokens.serializer(), original.codexTokens!!.getValue("offline-codex")))
            })
        }.toString()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, keyStore.getKey("pathpilot.configuration.v1", null) as SecretKey)
        }
        File(directory, "legacy/pathpilot-config.enc").writeBytes(
            byteArrayOf(1) + cipher.iv + cipher.doFinal(oldJson.toByteArray(Charsets.UTF_8)),
        )
        val migrated = open("legacy")
        assertNull(migrated.storageError.value)
        assertEquals(original.config, migrated.config.value)
        assertEquals(AppPreferences(), migrated.preferences.value)
        assertEquals(setOf("offline-codex"), migrated.authenticatedCodexProviders.value)
        assertEquals(8319, migrated.mcp.value.port)
        val mcpKey = migrated.mcp.value.accessKey
        assertTrue(mcpKey.startsWith("sk-"))
        assertEquals(mcpKey, open("legacy").mcp.value.accessKey)
    }

    private fun open(name: String): AppStore {
        val files = File(directory, name).apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir(): File = files
            override fun getApplicationContext(): Context = this
        }
        return AppStore(context).also(stores::add)
    }

    private fun key(char: Char) = "sk-" + char.toString().repeat(40)

    private fun snapshot(includeSession: Boolean): BackupSnapshot = BackupSnapshot(
        createdAtEpochMs = 1_795_584_000_000,
        config = RouterConfig(port = 8317, accessKey = key('a'), providers = listOf(
            ProviderConfig("offline-codex", "Codex · 离线测试", ProviderKind.CODEX, CodexBackend.BASE_URL,
                codexAccountId = "offline-account"),
            ProviderConfig("offline-api", "API · 离线测试", ProviderKind.OPENAI_COMPATIBLE,
                "https://api.example.test/v1", apiKey = "offline-api-key"),
        ), routes = listOf(RouteConfig("offline-model", "offline-api", "upstream-offline"))),
        preferences = AppPreferences(ThemeMode.DARK, false, AppLanguage.EN),
        mcp = McpSettings(port = 8318, accessKey = key('b'), connections = listOf(
            McpConnection(id = "offline-mcp", name = "工具 · 离线测试", url = "https://mcp.example.test/mcp",
                bearerToken = "offline-mcp-token", allowedTools = setOf("offline_echo")),
        )),
        codexTokens = if (includeSession) mapOf("offline-codex" to CodexTokens(
            accessToken = "offline-access", refreshToken = "offline-refresh", accountId = "offline-account",
            expiresAtEpochSeconds = System.currentTimeMillis() / 1000 + 86_400,
        )) else null,
    )
}
