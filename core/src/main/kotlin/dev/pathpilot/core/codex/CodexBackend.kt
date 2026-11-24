package dev.pathpilot.core.codex

import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** HTTP/SSE subset of the Codex subscription backend; deliberately not an arbitrary OAuth proxy. */
object CodexBackend {
    const val BASE_URL = "https://chatgpt.com/backend-api/codex"

    fun prepare(body: JsonObject, accessToken: String, accountId: String): Request {
        require(accessToken.isNotBlank() && accountId.isNotBlank()) { "Sign in to Codex first" }
        return Request.Builder()
            .url("$BASE_URL/responses")
            .header("Authorization", "Bearer $accessToken")
            .header("ChatGPT-Account-ID", accountId)
            .header("Accept", "text/event-stream")
            .header("User-Agent", "PathPilot/0.1 Android")
            .header("originator", "codex_cli_rs")
            .post(normalize(body).toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    /** Caller must preserve SSE or collect response.completed when the LAN client asks for JSON. */
    fun normalize(body: JsonObject): JsonObject {
        require((body["model"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true) { "model is required" }
        require(body["previous_response_id"] == null || body["previous_response_id"] == JsonNull) {
            "Codex HTTP routing requires full input history; previous_response_id is unsupported"
        }
        require((body["background"] as? JsonPrimitive)?.booleanOrNull != true) {
            "Codex HTTP routing does not support background responses"
        }
        val input = when (val value = body["input"]) {
            is JsonArray -> value
            is JsonPrimitive -> {
                require(value.isString) { "input must be text or an array" }
                JsonArray(listOf(message("user", value.content)))
            }
            else -> throw IllegalArgumentException("input is required and must be text or an array")
        }
        val instructionValue = body["instructions"]
        require(instructionValue == null || instructionValue == JsonNull ||
            instructionValue is JsonPrimitive && instructionValue.isString) {
            "instructions must be text"
        }
        val instructions = (instructionValue as? JsonPrimitive)?.contentOrNull
        val includeValue = body["include"]
        require(includeValue == null || includeValue == JsonNull ||
            includeValue is JsonArray && includeValue.all { it is JsonPrimitive && it.isString }) {
            "include must be an array of strings"
        }
        val normalizedInput = if (!instructions.isNullOrBlank()) {
            JsonArray(listOf(message("developer", instructions)) + input)
        } else input
        return buildJsonObject {
            body.forEach { (key, value) ->
                if (key !in setOf("instructions", "previous_response_id", "background")) put(key, value)
            }
            put("input", normalizedInput)
            put("store", false)
            put("stream", true)
            if ("tools" !in body) put("tools", JsonArray(emptyList()))
            if ("tool_choice" !in body) put("tool_choice", "auto")
            if ("parallel_tool_calls" !in body) put("parallel_tool_calls", true)
            val includes = (body["include"] as? JsonArray).orEmpty()
            val encryptedReasoning = JsonPrimitive("reasoning.encrypted_content")
            put("include", JsonArray(if (encryptedReasoning in includes) includes else includes + encryptedReasoning))
        }
    }

    private fun message(role: String, text: String) = buildJsonObject {
        put("type", "message")
        put("role", role)
        put("content", buildJsonArray {
            add(buildJsonObject { put("type", "input_text"); put("text", text) })
        })
    }
}
