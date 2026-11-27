package dev.pathpilot.app

import dev.pathpilot.core.mcp.McpConnection
import dev.pathpilot.core.mcp.McpValidation
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Curated remote services; saved connections remain editable, ordinary MCP connections. */
internal data class McpPreset(
    val id: String,
    val name: String,
    val url: String,
    val description: Int,
    val tokenLabel: Int? = null,
    val tokenUrl: String? = null,
) {
    val requiresToken: Boolean get() = tokenLabel != null

    fun connection(token: String = ""): McpConnection {
        val bearerToken = if (requiresToken) token.trim() else ""
        require(!requiresToken || bearerToken.isNotEmpty()) { "Enter a token" }
        return McpValidation.normalized(McpConnection(name = name, url = url, bearerToken = bearerToken))
    }

    fun existingConnection(connections: List<McpConnection>, token: String = ""): McpConnection? {
        val bearerToken = if (requiresToken) token.trim() else ""
        if (requiresToken && bearerToken.isEmpty()) return null
        return connections.firstOrNull {
            it.url.toHttpUrlOrNull() == url.toHttpUrlOrNull() && it.bearerToken == bearerToken
        }
    }
}

internal object McpPresets {
    val all = listOf(
        McpPreset(
            id = "microsoft-learn", name = "Microsoft Learn", url = "https://learn.microsoft.com/api/mcp",
            description = R.string.mcp_preset_learn_description,
        ),
        McpPreset(
            id = "openai-docs", name = "OpenAI Docs", url = "https://developers.openai.com/mcp",
            description = R.string.mcp_preset_openai_description,
        ),
        McpPreset(
            id = "context7", name = "Context7", url = "https://mcp.context7.com/mcp",
            description = R.string.mcp_preset_context7_description,
            tokenLabel = R.string.mcp_preset_api_key, tokenUrl = "https://context7.com/dashboard?tab=settings",
        ),
        McpPreset(
            id = "github", name = "GitHub", url = "https://api.githubcopilot.com/mcp/",
            description = R.string.mcp_preset_github_description,
            tokenLabel = R.string.mcp_preset_personal_token,
            tokenUrl = "https://github.com/settings/personal-access-tokens",
        ),
    )
}
