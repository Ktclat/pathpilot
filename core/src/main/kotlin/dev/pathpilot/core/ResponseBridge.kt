package dev.pathpilot.core

import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

/** Explicit Chat Completions/Responses bridge for the Codex subscription backend. */
internal object ResponseBridge {
    private val json = Json { ignoreUnknownKeys = true }

    fun chatToResponses(chat: JsonObject): JsonObject {
        require(chat["n"]?.jsonPrimitive?.intOrNull?.let { it == 1 } != false) { "Codex supports n=1 only." }
        for (key in listOf("audio", "modalities", "logprobs", "top_logprobs", "response_format", "seed",
            "temperature", "top_p", "max_tokens", "max_completion_tokens", "stop", "presence_penalty", "frequency_penalty", "logit_bias")) {
            require(chat[key] == null || chat[key] is JsonNull) { "Codex chat bridge does not support $key." }
        }
        val messages = chat["messages"] as? JsonArray ?: throw IllegalArgumentException("messages must be an array.")
        require(messages.isNotEmpty()) { "messages must not be empty." }
        val input = buildJsonArray {
            messages.forEach { element ->
                val message = element as? JsonObject ?: throw IllegalArgumentException("Invalid message.")
                val role = message["role"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Message role is required.")
                require(role in setOf("system", "developer", "user", "assistant", "tool")) { "Unsupported message role." }
                if (role == "tool") {
                    add(buildJsonObject {
                        put("type", "function_call_output")
                        put("call_id", message["tool_call_id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("tool_call_id required."))
                        put("output", message["content"]?.jsonPrimitive?.contentOrNull ?: "")
                    })
                } else {
                    val content = message["content"]
                    if (content != null && content !is JsonNull) add(buildJsonObject {
                        put("type", "message")
                        put("role", if (role == "system") "developer" else role)
                        put("content", buildJsonArray {
                            when (content) {
                                is JsonPrimitive -> add(buildJsonObject { put("type", if (role == "assistant") "output_text" else "input_text"); put("text", content.content) })
                                is JsonArray -> content.forEach { part ->
                                    val obj = part.jsonObject
                                    when (obj["type"]?.jsonPrimitive?.content) {
                                        "text" -> add(buildJsonObject { put("type", if (role == "assistant") "output_text" else "input_text"); put("text", obj["text"] ?: JsonPrimitive("")) })
                                        "image_url" -> add(buildJsonObject {
                                            put("type", "input_image")
                                            val image = obj["image_url"]!!.jsonObject
                                            put("image_url", image["url"]!!)
                                            image["detail"]?.let { put("detail", it) }
                                        })
                                        else -> throw IllegalArgumentException("Unsupported message content type.")
                                    }
                                }
                                else -> throw IllegalArgumentException("Unsupported message content.")
                            }
                        })
                    })
                    (message["tool_calls"] as? JsonArray)?.forEach { call ->
                        val obj = call.jsonObject
                        require(obj["type"]?.jsonPrimitive?.content == "function") { "Only function tools are supported." }
                        val function = obj["function"]!!.jsonObject
                        add(buildJsonObject {
                            put("type", "function_call"); put("call_id", obj["id"]!!)
                            put("name", function["name"]!!); put("arguments", function["arguments"]!!)
                        })
                    }
                }
            }
        }
        return buildJsonObject {
            put("model", chat["model"]!!)
            put("input", input)
            put("instructions", "")
            put("stream", true)
            put("store", false)
            (chat["tools"] as? JsonArray)?.let { tools ->
                put("tools", buildJsonArray {
                    tools.forEach { tool ->
                        val obj = tool.jsonObject
                        require(obj["type"]?.jsonPrimitive?.content == "function") { "Only function tools are supported." }
                        add(buildJsonObject {
                            put("type", "function")
                            obj["function"]!!.jsonObject.forEach { (key, value) -> put(key, value) }
                        })
                    }
                })
            }
            chat["tool_choice"]?.let { choice ->
                put("tool_choice", if (choice is JsonObject) buildJsonObject {
                    put("type", "function"); put("name", choice["function"]!!.jsonObject["name"]!!)
                } else choice)
            }
            for (key in listOf("parallel_tool_calls", "reasoning", "metadata")) chat[key]?.let { put(key, it) }
            chat["reasoning_effort"]?.let { put("reasoning", buildJsonObject { put("effort", it) }) }
        }
    }

    fun aggregate(source: InputStream): JsonObject {
        val events = SseReader(source)
        while (true) {
            val event = events.next() ?: throw IOException("Upstream ended before response completion.")
            when (event["type"]?.jsonPrimitive?.content) {
                "response.completed", "response.incomplete" -> return event["response"]!!.jsonObject
                "response.failed", "error" -> throw IOException("Upstream response failed.")
            }
        }
    }

    fun asChat(response: JsonObject, alias: String): JsonObject {
        val output = response["output"] as? JsonArray ?: JsonArray(emptyList())
        val messageContent = output.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "message" }
            .flatMap { (it.jsonObject["content"] as? JsonArray).orEmpty() }
        val text = messageContent
            .filter { it.jsonObject["type"]?.jsonPrimitive?.content == "output_text" }
            .joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
        val refusal = messageContent
            .filter { it.jsonObject["type"]?.jsonPrimitive?.content == "refusal" }
            .joinToString("") { it.jsonObject["refusal"]?.jsonPrimitive?.content.orEmpty() }
        val calls = output.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call" }
        return buildJsonObject {
            put("id", response["id"] ?: JsonPrimitive("chatcmpl-pathpilot")); put("object", "chat.completion")
            put("created", response["created_at"] ?: JsonPrimitive(System.currentTimeMillis() / 1000)); put("model", alias)
            put("choices", buildJsonArray { add(buildJsonObject {
                put("index", 0)
                put("message", buildJsonObject {
                    put("role", "assistant"); put("content", if (text.isEmpty() && (calls.isNotEmpty() || refusal.isNotEmpty())) JsonNull else JsonPrimitive(text))
                    if (refusal.isNotEmpty()) put("refusal", refusal)
                    if (calls.isNotEmpty()) put("tool_calls", buildJsonArray { calls.forEach { call -> add(buildJsonObject {
                        val obj = call.jsonObject
                        put("id", obj["call_id"]!!); put("type", "function")
                        put("function", buildJsonObject { put("name", obj["name"]!!); put("arguments", obj["arguments"]!!) })
                    }) } })
                })
                put("finish_reason", if (response["status"]?.jsonPrimitive?.content == "incomplete") "length" else if (calls.isEmpty()) "stop" else "tool_calls")
            }) })
            response["usage"]?.jsonObject?.let { put("usage", chatUsage(it)) }
        }
    }

    private fun chatUsage(usage: JsonObject) = buildJsonObject {
        put("prompt_tokens", usage["input_tokens"] ?: JsonPrimitive(0))
        put("completion_tokens", usage["output_tokens"] ?: JsonPrimitive(0))
        put("total_tokens", usage["total_tokens"] ?: JsonPrimitive(0))
        usage["input_tokens_details"]?.let { put("prompt_tokens_details", it) }
        usage["output_tokens_details"]?.let { put("completion_tokens_details", it) }
    }

    fun chatStream(source: InputStream, alias: String, includeUsage: Boolean): InputStream = object : InputStream() {
        val reader = SseReader(source)
        var buffer = ByteArray(0)
        var offset = 0
        var finished = false
        var id = "chatcmpl-pathpilot"
        var sentRole = false
        val toolIndices = mutableMapOf<String, Int>()
        var created = System.currentTimeMillis() / 1000

        fun chunk(delta: JsonObject, reason: String? = null): String = buildJsonObject {
            put("id", id); put("object", "chat.completion.chunk"); put("created", created); put("model", alias)
            put("choices", buildJsonArray { add(buildJsonObject {
                put("index", 0); put("delta", delta); put("finish_reason", reason?.let(::JsonPrimitive) ?: JsonNull)
            }) })
        }.toString()

        fun fill(): Boolean {
            while (!finished) {
                val event = reader.next()
                var output = ""
                val type = event?.get("type")?.jsonPrimitive?.content
                if (type == "response.created") event["response"]?.jsonObject?.let { response ->
                    id = response["id"]?.jsonPrimitive?.content ?: id
                    created = response["created_at"]?.jsonPrimitive?.longOrNull ?: created
                }
                if (!sentRole) {
                    output += "data: ${chunk(buildJsonObject { put("role", "assistant"); put("content", "") })}\n\n"
                    sentRole = true
                }
                when (type) {
                    "response.created" -> {
                        event["response"]?.jsonObject?.let { response ->
                            id = response["id"]?.jsonPrimitive?.content ?: id
                            created = response["created_at"]?.jsonPrimitive?.longOrNull ?: created
                        }
                    }
                    "response.output_text.delta" -> output += "data: ${chunk(buildJsonObject { put("content", event["delta"]!!) })}\n\n"
                    "response.refusal.delta" -> output += "data: ${chunk(buildJsonObject { put("refusal", event["delta"]!!) })}\n\n"
                    "response.output_item.added" -> {
                        val item = event["item"]!!.jsonObject
                        if (item["type"]?.jsonPrimitive?.content == "function_call") {
                            val itemId = item["id"]!!.jsonPrimitive.content
                            val index = toolIndices.getOrPut(itemId) { toolIndices.size }
                            output += "data: ${chunk(buildJsonObject { put("tool_calls", buildJsonArray { add(buildJsonObject {
                                put("index", index); put("id", item["call_id"]!!); put("type", "function")
                                put("function", buildJsonObject { put("name", item["name"]!!); put("arguments", item["arguments"] ?: JsonPrimitive("")) })
                            }) }) })}\n\n"
                        }
                    }
                    "response.function_call_arguments.delta" -> {
                        val itemId = event["item_id"]!!.jsonPrimitive.content
                        val index = toolIndices[itemId] ?: throw IOException("Tool delta arrived before tool declaration.")
                        output += "data: ${chunk(buildJsonObject { put("tool_calls", buildJsonArray { add(buildJsonObject {
                            put("index", index); put("function", buildJsonObject { put("arguments", event["delta"]!!) })
                        }) }) })}\n\n"
                    }
                    "response.completed", "response.incomplete" -> {
                        val response = event["response"]!!.jsonObject
                        output += "data: ${chunk(buildJsonObject {}, if (type == "response.incomplete") "length" else if (toolIndices.isEmpty()) "stop" else "tool_calls")}\n\n"
                        if (includeUsage) response["usage"]?.jsonObject?.let { usage ->
                            output += "data: ${buildJsonObject {
                                put("id", id); put("object", "chat.completion.chunk"); put("created", created); put("model", alias)
                                put("choices", JsonArray(emptyList())); put("usage", chatUsage(usage))
                            }}\n\n"
                        }
                        output += "data: [DONE]\n\n"; finished = true
                    }
                    null, "response.failed", "error" -> {
                        output += "data: {\"error\":{\"type\":\"upstream_error\",\"message\":\"Upstream response did not complete.\"}}\n\ndata: [DONE]\n\n"
                        finished = true
                    }
                }
                if (output.isNotEmpty()) { buffer = output.toByteArray(Charsets.UTF_8); offset = 0; return true }
            }
            return false
        }

        override fun read(): Int {
            if (offset >= buffer.size && !fill()) return -1
            return buffer[offset++].toInt() and 255
        }
        override fun read(bytes: ByteArray, start: Int, length: Int): Int {
            if (length == 0) return 0
            if (offset >= buffer.size && !fill()) return -1
            val count = minOf(length, buffer.size - offset)
            buffer.copyInto(bytes, start, offset, offset + count); offset += count
            return count
        }
        override fun close() = source.close()
    }

    /** Bounded individual events prevent an upstream from allocating unbounded memory. */
    internal class SseReader(source: InputStream) {
        private val reader = BufferedReader(InputStreamReader(source, Charsets.UTF_8))
        fun next(): JsonObject? {
            val data = StringBuilder()
            while (true) {
                val line = readBoundedLine() ?: return if (data.isEmpty()) null else parse(data.toString())
                if (line.isEmpty()) {
                    if (data.isNotEmpty()) return parse(data.toString())
                } else if (line.startsWith("data:")) {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring(5).removePrefix(" "))
                    if (data.length > MAX_EVENT) throw IOException("Upstream event exceeds size limit.")
                }
            }
        }
        private fun readBoundedLine(): String? {
            val line = StringBuilder()
            while (true) {
                val char = reader.read()
                if (char == -1) return if (line.isEmpty()) null else line.toString()
                if (char == 10) return line.toString().removeSuffix("\r")
                line.append(char.toChar())
                if (line.length > MAX_EVENT) throw IOException("Upstream event exceeds size limit.")
            }
        }
        private fun parse(data: String): JsonObject? = if (data == "[DONE]") null else json.parseToJsonElement(data).jsonObject
        companion object { private const val MAX_EVENT = 16 * 1024 * 1024 }
    }
}
