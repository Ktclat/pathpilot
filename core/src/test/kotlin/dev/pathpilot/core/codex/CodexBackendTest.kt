package dev.pathpilot.core.codex

import kotlinx.serialization.json.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class CodexBackendTest {
    @Test fun normalizesResponsesInputAndForcesStatelessStreamingTransport() {
        val request = CodexBackend.prepare(Json.parseToJsonElement("""{
            "model":"gpt-5-codex", "input":"Hello", "instructions":"Be concise",
            "stream":false,"store":true,"reasoning":{"effort":"low"}
        }""").jsonObject, "test-access", "test-account")
        assertEquals("https://chatgpt.com/backend-api/codex/responses", request.url.toString())
        assertEquals("Bearer test-access", request.header("Authorization"))
        assertEquals("test-account", request.header("ChatGPT-Account-ID"))
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
        assertEquals(JsonPrimitive(true), body["stream"])
        assertEquals(JsonPrimitive(false), body["store"])
        assertFalse("instructions" in body)
        assertEquals("developer", body["input"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("user", body["input"]!!.jsonArray[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("low", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertTrue(JsonPrimitive("reasoning.encrypted_content") in body["include"]!!.jsonArray)
    }

    @Test fun rejectsUnsupportedStatefulRequestsRatherThanDroppingHistory() {
        assertThrows(IllegalArgumentException::class.java) {
            CodexBackend.normalize(Json.parseToJsonElement("""{"model":"model","input":[],"previous_response_id":"resp-old"}""").jsonObject)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CodexBackend.normalize(Json.parseToJsonElement("""{"model":"model","input":[],"background":true}""").jsonObject)
        }
    }

    @Test fun retainsToolInputAndCalls() {
        val body = Json.parseToJsonElement("""{"model":"model","input":[{"type":"function_call_output","call_id":"call_1","output":"done"}],"tools":[{"type":"function","name":"read_file","parameters":{"type":"object"}}]}""").jsonObject
        val normalized = CodexBackend.normalize(body)
        assertEquals(body["input"], normalized["input"])
        assertEquals(body["tools"], normalized["tools"])
    }
}
