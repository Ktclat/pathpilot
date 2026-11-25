package dev.pathpilot.core.mcp

import dev.pathpilot.core.AccessKeys
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.UUID

const val MCP_PROTOCOL_VERSION = "2025-11-25"

@Serializable
data class McpConnection(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val bearerToken: String = "",
    val enabled: Boolean = true,
    /** null shares all tools; an empty set shares none. */
    val allowedTools: Set<String>? = null,
) {
    override fun toString() = "McpConnection(id=$id, name=$name, enabled=$enabled)"
}

@Serializable
data class McpSettings(
    val port: Int = 8318,
    val accessKey: String = AccessKeys.generate(),
    val connections: List<McpConnection> = emptyList(),
) {
    override fun toString() = "McpSettings(port=$port, connections=$connections)"
}

object McpValidation {
    fun normalized(connection: McpConnection): McpConnection {
        require(connection.id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid MCP connection ID" }
        require(connection.name.trim().isNotEmpty()) { "Enter a connection name" }
        val url = connection.url.trim().toHttpUrlOrNull()
        require(url != null && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) {
            "Enter a complete HTTP or HTTPS MCP URL without user information or a fragment"
        }
        val token = connection.bearerToken.trim()
        require(token.none { it <= ' ' || it > '~' }) { "Bearer token must not contain whitespace or control characters" }
        require(connection.allowedTools?.none(String::isBlank) != false) { "Tool names must not be blank" }
        return connection.copy(name = connection.name.trim(), url = url.toString(), bearerToken = token)
    }

    fun normalized(settings: McpSettings): McpSettings {
        require(settings.port in 1024..65535) { "MCP port must be between 1024 and 65535" }
        require(settings.accessKey.length >= 24 && settings.accessKey.none { it <= ' ' || it > '~' }) {
            "MCP access key must contain at least 24 characters without whitespace"
        }
        val connections = settings.connections.map(::normalized)
        require(connections.map { it.id }.distinct().size == connections.size) { "Duplicate MCP connection ID" }
        return settings.copy(connections = connections)
    }
}

data class McpServerInfo(val name: String, val protocolVersion: String)

data class McpTool(val definition: JsonObject) {
    val name: String get() = (definition["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val description: String? get() = (definition["description"] as? JsonPrimitive)?.contentOrNull
}

data class McpCallEvent(
    val connectionId: String,
    val connectionName: String,
    val toolName: String,
    val success: Boolean,
    val durationMs: Long,
    val errorCode: String? = null,
    val startedAtEpochMs: Long = System.currentTimeMillis(),
    val id: String = UUID.randomUUID().toString(),
)

class McpException(
    message: String,
    val code: Int? = null,
    val httpStatus: Int? = null,
    val data: kotlinx.serialization.json.JsonElement? = null,
    cause: Throwable? = null,
) : IOException(message, cause)
