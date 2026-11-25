package dev.pathpilot.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream

/** Observes response metadata without delaying reads or changing bytes sent to the caller. */
internal class ResponseObservation(private val sse: Boolean) {
    var usage: TokenUsage? = null
        private set
    var terminal = false
        private set
    var errorCode: String? = null
        private set
    var incomplete = false
        private set

    private val buffer = ByteArrayOutputStream()
    private var oversized = false
    private var prefix = ""
    private var lineHasContent = false
    private var carriageReturn = false
    private var finished = false

    fun wrap(source: InputStream): InputStream = object : FilterInputStream(source) {
        override fun read(): Int = `in`.read().also { value ->
            if (value < 0) finish() else accept(byteArrayOf(value.toByte()), 0, 1)
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = `in`.read(bytes, offset, length).also { count ->
            if (count < 0) finish() else accept(bytes, offset, count)
        }
    }

    private fun collect(bytes: ByteArray, offset: Int, length: Int) {
        if (oversized) return
        if (length > MAX_METADATA_BYTES - buffer.size()) {
            val remaining = MAX_METADATA_BYTES - buffer.size()
            buffer.write(bytes, offset, remaining)
            prefix = buffer.toByteArray().let { String(it, 0, minOf(it.size, 1024), Charsets.UTF_8) }
            buffer.reset()
            oversized = true
        } else buffer.write(bytes, offset, length)
    }

    private fun accept(bytes: ByteArray, offset: Int, length: Int) {
        if (!sse) { collect(bytes, offset, length); return }
        for (index in offset until offset + length) {
            val value = bytes[index].toInt() and 255
            if (value == 10 && carriageReturn) { carriageReturn = false; continue }
            carriageReturn = value == 13
            if (value == 10 || value == 13) {
                if (!lineHasContent) consumeEvent()
                else {
                    if (!oversized && buffer.size() < MAX_METADATA_BYTES) buffer.write(10)
                    lineHasContent = false
                }
            } else {
                lineHasContent = true
                collect(bytes, index, 1)
            }
        }
    }

    private fun finish() {
        if (finished) return
        finished = true
        if (sse) consumeEvent()
        else if (!oversized) decode(buffer.toString(Charsets.UTF_8.name()))?.let(::observe)
        buffer.reset()
    }

    private fun consumeEvent() {
        if (buffer.size() == 0 && !oversized) return
        val frame = if (oversized) prefix else buffer.toString(Charsets.UTF_8.name())
        val eventType = frame.lineSequence().firstOrNull { it.startsWith("event:") }
            ?.substring(6)?.trim()
        val data = frame.lineSequence().filter { it.startsWith("data:") }
            .joinToString("\n") { it.substring(5).removePrefix(" ") }
        if (oversized) {
            // Official Responses events put type first; a large output must not hide its terminal marker.
            val firstType = TYPE_PREFIX.find(data)?.groupValues?.get(1)
            observeType(eventType ?: firstType)
        } else if (data.trim() == "[DONE]") terminal = true
        else {
            observeType(eventType)
            decode(data)?.let(::observe)
        }
        buffer.reset()
        oversized = false
        prefix = ""
    }

    private fun observe(value: JsonObject) {
        val response = value["response"] as? JsonObject
        parseTokenUsage((value["usage"] as? JsonObject) ?: (response?.get("usage") as? JsonObject))?.let { usage = it }
        observeType((value["type"] as? JsonPrimitive)?.contentOrNull)
        when (((response ?: value)["status"] as? JsonPrimitive)?.contentOrNull) {
            "completed" -> terminal = true
            "incomplete" -> { terminal = true; incomplete = true; errorCode = "upstream_response_incomplete" }
            "failed" -> { terminal = true; errorCode = "upstream_response_failed" }
        }
        if (value["error"] != null && value["error"] !is JsonNull) {
            terminal = true
            errorCode = "upstream_response_failed"
        }
    }

    private fun observeType(type: String?) {
        when (type) {
            "response.completed" -> terminal = true
            "response.incomplete" -> { terminal = true; incomplete = true; errorCode = "upstream_response_incomplete" }
            "response.failed", "error" -> { terminal = true; errorCode = "upstream_response_failed" }
        }
    }

    private fun decode(value: String): JsonObject? = try {
        Json.parseToJsonElement(value) as? JsonObject
    } catch (_: SerializationException) { null }

    companion object {
        // This bounds observation only; oversized bodies/events are still forwarded unchanged.
        private const val MAX_METADATA_BYTES = 4 * 1024 * 1024
        private val TYPE_PREFIX = Regex("^\\s*\\{\\s*\"type\"\\s*:\\s*\"([^\"]+)\"")
    }
}

internal fun parseTokenUsage(value: JsonObject?): TokenUsage? {
    if (value == null) return null
    fun JsonObject.count(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
    val input = value.count("input_tokens") ?: value.count("prompt_tokens")
    val output = value.count("output_tokens") ?: value.count("completion_tokens")
    val total = value.count("total_tokens") ?: if (input != null && output != null && input <= Long.MAX_VALUE - output) input + output else null
    val inputDetails = (value["input_tokens_details"] ?: value["prompt_tokens_details"]) as? JsonObject
    val outputDetails = (value["output_tokens_details"] ?: value["completion_tokens_details"]) as? JsonObject
    val cached = inputDetails?.count("cached_tokens") ?: value.count("prompt_cache_hit_tokens")
    val reasoning = outputDetails?.count("reasoning_tokens")
    return if (listOf(input, output, total, cached, reasoning).all { it == null }) null
        else TokenUsage(input, output, total, cached, reasoning)
}
