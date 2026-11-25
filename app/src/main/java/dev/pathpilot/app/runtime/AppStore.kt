package dev.pathpilot.app.runtime

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Log
import dev.pathpilot.core.AccessKeys
import dev.pathpilot.core.CodexCredentials
import dev.pathpilot.core.ProviderConfig
import dev.pathpilot.core.RouterConfig
import dev.pathpilot.core.codex.CodexOAuthClient
import dev.pathpilot.core.codex.CodexTokens
import dev.pathpilot.core.network.OutboundProxyValidation
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The complete configuration (including upstream and LAN credentials) is encrypted at rest. */
class AppStore private constructor(context: Context) {
    val requestHistory = RequestHistoryStore(context)
    private val file = AtomicFile(File(context.noBackupFilesDir, "pathpilot-config.enc"))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var stored = StoredState(RouterConfig(accessKey = AccessKeys.generate()))
    // A successful OAuth refresh may invalidate the old refresh token even if our disk write
    // subsequently fails. Keep the replacement until it is durable; never send it upstream first.
    private val pendingCodexTokens = mutableMapOf<String, CodexTokens>()
    private val mutableConfig = MutableStateFlow(stored.config)
    val config: StateFlow<RouterConfig> = mutableConfig.asStateFlow()
    private val mutableAccounts = MutableStateFlow<Set<String>>(emptySet())
    val authenticatedCodexProviders: StateFlow<Set<String>> = mutableAccounts.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val storageError: StateFlow<String?> = mutableError.asStateFlow()
    private val oauth by lazy { CodexOAuthClient(proxyProvider = { config.value.outboundProxy }) }

    init {
        try {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
                val bytes = file.readFully()
                require(bytes.size > 29 && bytes[0] == FORMAT_VERSION) { "Invalid encrypted configuration" }
                val iv = bytes.copyOfRange(1, 13)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                val cleartext = cipher.doFinal(bytes.copyOfRange(13, bytes.size))
                stored = json.decodeFromString<StoredState>(cleartext.toString(Charsets.UTF_8))
                publish()
            } else {
                persist(stored)
            }
        } catch (error: Exception) {
            // Do not overwrite an existing file when the OS key is unavailable or data is corrupt.
            Log.e("PathPilotStore", "Configuration load failed: ${error.javaClass.simpleName}")
            mutableError.value = "无法读取加密配置。请重启应用后重试。"
        }
    }

    @Synchronized
    fun setConfig(config: RouterConfig) {
        ensureUsable()
        require(config.port in 1024..65535) { "端口必须在 1024–65535 之间" }
        require(config.accessKey.length >= 24) { "共享访问密钥至少需要 24 个字符" }
        require(config.providers.map { it.id }.distinct().size == config.providers.size) { "供应商 ID 重复" }
        require(config.routes.map { it.alias }.distinct().size == config.routes.size) { "模型别名重复" }
        val retained = stored.codexTokens.filterKeys { id -> config.providers.any { it.id == id } }
        val normalized = config.copy(outboundProxy = OutboundProxyValidation.normalized(config.outboundProxy))
        commit(stored.copy(config = normalized, codexTokens = retained))
    }

    @Synchronized
    fun updateConfig(transform: (RouterConfig) -> RouterConfig) = setConfig(transform(stored.config))

    @Synchronized
    fun saveCodexTokens(providerId: String, tokens: CodexTokens) {
        ensureUsable()
        require(stored.config.providers.any { it.id == providerId }) { "请先保存 Codex 供应商" }
        // An explicit new login supersedes any older pending refresh for this provider. If the
        // write fails, retain this newly authorized session so retrying need not reauthorize.
        pendingCodexTokens[providerId] = tokens
        commit(stored)
    }

    /** Retry the current staged write, never resubmit a token that may since have rotated. */
    @Synchronized
    fun retryPendingCodexSave(providerId: String) {
        ensureUsable()
        require(stored.config.providers.any { it.id == providerId }) { "供应商已移除" }
        if (pendingCodexTokens.containsKey(providerId)) commit(stored)
        else check(stored.codexTokens.containsKey(providerId)) { "授权尚未保存，请重新登录" }
    }

    @Synchronized
    fun removeCodexTokens(providerId: String) {
        ensureUsable()
        commit(stored.copy(codexTokens = stored.codexTokens - providerId), discardPending = setOf(providerId))
    }

    @Synchronized
    fun hasCodexSession(providerId: String): Boolean = stored.codexTokens.containsKey(providerId)

    /** Called on the server worker thread. Synchronization prevents refresh-token rotation races. */
    @Synchronized
    fun resolveCodexCredentials(provider: ProviderConfig): CodexCredentials {
        ensureUsable()
        if (pendingCodexTokens.containsKey(provider.id)) {
            // Retry the failed encrypted write before considering another refresh. On failure,
            // commit leaves both the last durable state and the pending replacement untouched.
            commit(stored)
        }
        var tokens = stored.codexTokens[provider.id]
            ?: throw IllegalStateException("Codex 账号尚未登录")
        if (tokens.needsRefresh()) {
            tokens = oauth.refresh(tokens)
            pendingCodexTokens[provider.id] = tokens
            // Persist a rotated refresh token before returning its access token to the router.
            commit(stored)
        }
        return CodexCredentials(tokens.accessToken, tokens.accountId)
    }

    private fun ensureUsable() {
        check(mutableError.value == null) { mutableError.value ?: "加密配置不可用" }
    }

    private fun commit(next: StoredState, discardPending: Set<String> = emptySet()) {
        val retainedProviders = next.config.providers.mapTo(mutableSetOf()) { it.id }
        val pendingToPersist = pendingCodexTokens.filterKeys { it in retainedProviders && it !in discardPending }
        val durableNext = next.copy(codexTokens = next.codexTokens + pendingToPersist)
        // Config edits also persist pending sessions, so they cannot overwrite a rotated token
        // with the obsolete token still held in the last durable state. Deletion removes both.
        persist(durableNext)
        stored = durableNext
        pendingCodexTokens.clear()
        publish()
    }

    private fun publish() {
        mutableConfig.value = stored.config
        mutableAccounts.value = stored.codexTokens.keys.toSet()
    }

    private fun persist(next: StoredState) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(json.encodeToString(next).toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try {
            stream.write(byteArrayOf(FORMAT_VERSION))
            stream.write(cipher.iv)
            stream.write(encrypted)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }

    @Serializable
    private data class StoredState(
        val config: RouterConfig,
        val codexTokens: Map<String, CodexTokens> = emptyMap(),
    )

    companion object {
        private const val KEY_ALIAS = "pathpilot.configuration.v1"
        private const val FORMAT_VERSION: Byte = 1
        @Volatile private var instance: AppStore? = null
        fun get(context: Context): AppStore = instance ?: synchronized(this) {
            instance ?: AppStore(context.applicationContext).also { instance = it }
        }
    }
}
