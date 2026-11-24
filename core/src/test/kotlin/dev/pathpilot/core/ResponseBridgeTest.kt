package dev.pathpilot.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ResponseBridgeTest {
    @Test fun chatHistoryIncludesToolResultsAndImagesWithoutDroppingContent() {
        val chat = Json.parseToJsonElement("""{
          "model":"code","messages":[
            {"role":"system","content":"Be brief"},
            {"role":"user","content":[{"type":"text","text":"Inspect"},{"type":"image_url","image_url":{"url":"data:image/png;base64,abcd","detail":"low"}}]},
            {"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"lookup","arguments":"{}"}}]},
            {"role":"tool","tool_call_id":"call_1","content":"found"},
            {"role":"assistant","content":"Here is the result"}
          ],
          "tools":[{"type":"function","function":{"name":"lookup","parameters":{"type":"object","properties":{}}}}],
          "tool_choice":{"type":"function","function":{"name":"lookup"}}
        }""").jsonObject
        val result = ResponseBridge.chatToResponses(chat)
        val input = result["input"]!!.jsonArray
        assertEquals("developer", input[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("input_image", input[1].jsonObject["content"]!!.jsonArray[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("function_call", input[2].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", input[3].jsonObject["call_id"]!!.jsonPrimitive.content)
        assertEquals("output_text", input[4].jsonObject["content"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("lookup", result["tools"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("lookup", result["tool_choice"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test fun streamingToolCallsHaveStableIndicesAndUsage() {
        val sse = listOf(
            """{"type":"response.created","response":{"id":"resp_1","created_at":123}}""",
            """{"type":"response.output_item.added","item":{"id":"fc_1","type":"function_call","call_id":"call_1","name":"lookup","arguments":""}}""",
            """{"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"{\"query\":"}""",
            """{"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"\"android\"}"}""",
            """{"type":"response.completed","response":{"usage":{"input_tokens":3,"output_tokens":5,"total_tokens":8}}}""",
        ).joinToString("") { "data: $it\n\n" }
        val output = ResponseBridge.chatStream(sse.byteInputStream(), "code", true).bufferedReader().readText()
        assertTrue(output.endsWith("data: [DONE]\n\n"))
        val chunks = output.lineSequence().filter { it.startsWith("data: {") }.map { Json.parseToJsonElement(it.removePrefix("data: ")).jsonObject }.toList()
        assertTrue(chunks.all { it["id"]!!.jsonPrimitive.content == "resp_1" })
        val deltas = chunks.flatMap { it["choices"]!!.jsonArray }.map { it.jsonObject["delta"]!!.jsonObject }
        val toolDeltas = deltas.mapNotNull { it["tool_calls"]?.jsonArray?.get(0)?.jsonObject }
        assertEquals(3, toolDeltas.size)
        assertTrue(toolDeltas.all { it["index"]!!.jsonPrimitive.int == 0 })
        assertEquals("lookup", toolDeltas[0]["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("{\"query\":\"android\"}", toolDeltas.joinToString("") { it["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content })
        assertTrue(chunks.any { it["choices"]!!.jsonArray.firstOrNull()?.jsonObject?.get("finish_reason")?.jsonPrimitive?.contentOrNull == "tool_calls" })
        assertEquals(8, chunks.last()["usage"]!!.jsonObject["total_tokens"]!!.jsonPrimitive.int)
    }

    @Test fun completedRefusalIsPreservedInChatMessage() {
        val response = Json.parseToJsonElement("""{
          "id":"resp_refusal","status":"completed","output":[
            {"type":"message","role":"assistant","content":[
              {"type":"refusal","refusal":"I cannot help with that request."}
            ]}
          ]
        }""").jsonObject
        val result = ResponseBridge.asChat(response, "code")
        val choice = result["choices"]!!.jsonArray.single().jsonObject
        val message = choice["message"]!!.jsonObject
        assertEquals("I cannot help with that request.", message["refusal"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, message["content"])
        assertEquals("stop", choice["finish_reason"]!!.jsonPrimitive.content)
        assertEquals("code", result["model"]!!.jsonPrimitive.content)
    }

    @Test fun streamingRefusalIsPreservedWithoutRepeatingDoneOrCompletedText() {
        val sse = listOf(
            """{"type":"response.created","response":{"id":"resp_refusal","created_at":123}}""",
            """{"type":"response.refusal.delta","delta":"I cannot help "}""",
            """{"type":"response.refusal.delta","delta":"with that request."}""",
            """{"type":"response.refusal.done","refusal":"I cannot help with that request."}""",
            """{"type":"response.completed","response":{"status":"completed","output":[{"type":"message","content":[{"type":"refusal","refusal":"I cannot help with that request."}]}]}}""",
        ).joinToString("") { "data: $it\n\n" }
        val output = ResponseBridge.chatStream(sse.byteInputStream(), "code", false).bufferedReader().readText()
        val chunks = output.lineSequence().filter { it.startsWith("data: {") }
            .map { Json.parseToJsonElement(it.removePrefix("data: ")).jsonObject }.toList()
        val choices = chunks.flatMap { it["choices"]!!.jsonArray }.map { it.jsonObject }
        val refusalDeltas = choices.mapNotNull { it["delta"]!!.jsonObject["refusal"]?.jsonPrimitive?.content }
        assertEquals(listOf("I cannot help ", "with that request."), refusalDeltas)
        assertEquals("I cannot help with that request.", refusalDeltas.joinToString(""))
        assertEquals("stop", choices.last()["finish_reason"]!!.jsonPrimitive.content)
        assertTrue(chunks.all { it["id"]!!.jsonPrimitive.content == "resp_refusal" })
        assertTrue(output.endsWith("data: [DONE]\n\n"))
    }

    @Test fun truncatedStreamDoesNotPretendToComplete() {
        val sse = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n"
        val output = ResponseBridge.chatStream(sse.byteInputStream(), "code", false).bufferedReader().readText()
        assertTrue(output.contains("upstream_error"))
        assertFalse(output.contains("\"finish_reason\":\"stop\""))
        try { ResponseBridge.aggregate(sse.byteInputStream()); fail("Truncated stream must fail aggregation") } catch (_: IOException) { }
    }

    @Test fun streamCloseReleasesUpstream() {
        var closed = false
        val source = object : java.io.ByteArrayInputStream(ByteArray(0)) { override fun close() { closed = true; super.close() } }
        ResponseBridge.chatStream(source, "code", false).close()
        assertTrue(closed)
    }

    @Test fun unsupportedChatFeaturesAreExplicitlyRejected() {
        val chat = Json.parseToJsonElement("""{"model":"code","messages":[{"role":"user","content":"hi"}],"response_format":{"type":"json_object"}}""").jsonObject
        try { ResponseBridge.chatToResponses(chat); fail("Unsupported features must be rejected") } catch (_: IllegalArgumentException) { }
        listOf("temperature", "top_p", "max_tokens", "max_completion_tokens", "stop").forEach { field ->
            val request = Json.parseToJsonElement("""{"model":"code","messages":[{"role":"user","content":"hi"}],"$field":1}""").jsonObject
            try { ResponseBridge.chatToResponses(request); fail("$field must not be silently dropped") } catch (_: IllegalArgumentException) { }
        }
    }
}
