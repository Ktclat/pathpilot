package dev.pathpilot.app.settings

import dev.pathpilot.core.ProviderKind
import dev.pathpilot.core.RouterConfig
import dev.pathpilot.core.codex.CodexTokens
import dev.pathpilot.core.mcp.McpSettings
import dev.pathpilot.core.mcp.McpValidation
import dev.pathpilot.core.network.OutboundProxyValidation
import dev.pathpilot.core.providers.ProviderSetup
import kotlinx.serialization.Serializable

@Serializable
data class BackupSnapshot(
    val schemaVersion: Int = 1,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val config: RouterConfig,
    val preferences: AppPreferences = AppPreferences(),
    val mcp: McpSettings,
    /** null means sessions were omitted; an empty map is an included, empty session set. */
    val codexTokens: Map<String, CodexTokens>? = null,
) {
    val includesCodexSessions: Boolean get() = codexTokens != null
    override fun toString() = "BackupSnapshot(schemaVersion=$schemaVersion, providers=${config.providers.size}, routes=${config.routes.size}, mcpConnections=${mcp.connections.size}, includesCodexSessions=$includesCodexSessions)"
}

/** Validates the complete imported configuration before any local state is replaced. */
object BackupValidation {
    fun normalized(snapshot: BackupSnapshot): BackupSnapshot {
        if (snapshot.schemaVersion != 1) throw BackupException(BackupError.UNSUPPORTED_VERSION)
        require(snapshot.createdAtEpochMs >= 0) { "备份时间无效" }
        val config = snapshot.config
        require(config.port in 1024..65535) { "模型共享端口必须在 1024–65535 之间" }
        require(config.accessKey.length >= 24 && config.accessKey.none { it.code !in 33..126 }) { "模型共享密钥格式无效" }
        require(config.providers.all { it.id.isNotBlank() && it.id.none { char -> Character.isISOControl(char) } }) { "供应商 ID 无效" }
        require(config.providers.map { it.id }.distinct().size == config.providers.size) { "供应商 ID 重复" }
        val providers = config.providers.map { provider ->
            val result = ProviderSetup.validate(provider.name, provider.baseUrl, provider.apiKey, provider.kind)
            require(result.isValid) { result.nameError ?: result.urlError ?: result.keyError ?: "供应商配置无效" }
            provider.copy(name = provider.name.trim(), baseUrl = requireNotNull(result.normalizedBaseUrl), apiKey = provider.apiKey.trim())
        }
        require(config.routes.map { it.alias }.distinct().size == config.routes.size) { "模型别名重复" }
        val providerIds = providers.mapTo(mutableSetOf()) { it.id }
        config.routes.forEach { route ->
            require(route.providerId in providerIds) { "模型引用的供应商不存在" }
            require(route.alias.matches(Regex("[A-Za-z0-9._:/-]{1,128}"))) { "模型别名格式无效" }
            require(ProviderSetup.validateModelId(route.upstreamModel) == null && route.upstreamModel == route.upstreamModel.trim()) { "上游模型 ID 格式无效" }
        }
        val mcp = McpValidation.normalized(snapshot.mcp)
        require(config.port != mcp.port) { "模型与 MCP 共享端口不能相同" }
        snapshot.codexTokens?.forEach { (id, tokens) ->
            require(providers.any { it.id == id && it.kind == ProviderKind.CODEX }) { "登录状态引用的 Codex 供应商不存在" }
            require(providers.first { it.id == id }.codexAccountId.let { it.isBlank() || it == tokens.accountId }) { "Codex 账号与登录状态不匹配" }
            require(tokens.accessToken.isNotBlank() && tokens.refreshToken.isNotBlank() && tokens.accountId.isNotBlank()) { "Codex 登录状态不完整" }
            require(tokens.expiresAtEpochSeconds > 0) { "Codex 登录状态的有效期无效" }
        }
        return snapshot.copy(config = config.copy(providers = providers,
            outboundProxy = OutboundProxyValidation.normalized(config.outboundProxy)), mcp = mcp)
    }
}
