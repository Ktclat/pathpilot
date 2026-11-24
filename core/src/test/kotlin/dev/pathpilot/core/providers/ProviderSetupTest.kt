package dev.pathpilot.core.providers

import dev.pathpilot.core.ProviderKind
import dev.pathpilot.core.codex.CodexBackend
import org.junit.Assert.*
import org.junit.Test

class ProviderSetupTest {
    private fun validate(url: String, key: String = "secret-key", name: String = "DeepSeek") =
        ProviderSetup.validate(name, url, key, ProviderKind.DEEPSEEK)

    @Test fun normalizesTrailingSlashWithoutInventingProviderPath() {
        assertEquals("https://example.com/custom/api", validate(" https://example.com/custom/api/// ").normalizedBaseUrl)
        assertEquals("https://example.com", validate("https://example.com/").normalizedBaseUrl)
        assertTrue(validate("https://example.com/v1", "  secret-key  ", " 测试供应商 ").isValid)
    }

    @Test fun onlyAbsoluteHttpsHostWithoutUserInfoQueryFragmentOrControlsIsAccepted() {
        listOf("example.com/v1", "/v1", "http://example.com/v1", "https:///", "https://user:pass@example.com/v1",
            "https://@example.com/v1", "https://example.com/v1?key=secret", "https://example.com/v1#fragment",
            "https://example.com/v1?", "https://example.com/v1#", "https://example.com/\nv1", "https://example.com\\v1",
            "https://example.com/some path").forEach { url ->
            val result = validate(url)
            assertFalse("Should reject invalid URL", result.isValid)
            assertNotNull(result.urlError)
            assertNull(result.normalizedBaseUrl)
        }
        assertTrue(validate("https://localhost:8443/custom").isValid)
        assertTrue(validate("https://[::1]:8443/custom").isValid)
    }

    @Test fun pastedOperationEndpointsGiveActionableFieldError() {
        listOf("chat/completions", "responses", "models").forEach { endpoint ->
            val result = validate("https://example.com/v1/$endpoint/")
            assertFalse(result.isValid)
            assertTrue(result.urlError!!.contains("去掉末尾的 /$endpoint"))
        }
        assertTrue(validate("https://example.com/my-responses-proxy/v1").isValid)
    }

    @Test fun apiKeyMustBeHeaderSafeAndErrorsNeverEchoInput() {
        listOf("", "   ", "secret key", "secret\nkey", "secret\r\nX-Other:evil", "secret\tkey", "密钥", "secret\u007fkey").forEach { key ->
            val result = validate("https://example.com/v1", key)
            assertNotNull(result.keyError)
            assertFalse(result.toString().contains("secret"))
        }
        assertTrue(validate("https://example.com", "sk_ABC-123:./+=").isValid)
    }

    @Test fun fieldLengthsAndModelIdsAreValidatedWithoutTruncation() {
        assertNotNull(validate("https://example.com", name = "a".repeat(ProviderSetup.MAX_NAME_LENGTH + 1)).nameError)
        assertNotNull(validate("https://example.com/" + "a".repeat(ProviderSetup.MAX_BASE_URL_LENGTH)).urlError)
        assertNotNull(validate("https://example.com", "a".repeat(ProviderSetup.MAX_API_KEY_LENGTH + 1)).keyError)
        assertNull(ProviderSetup.validateModelId("@cf/meta/llama-3:latest"))
        assertNull(ProviderSetup.validateModelId("模型/新版"))
        assertNotNull(ProviderSetup.validateModelId("bad model"))
        assertNotNull(ProviderSetup.validateModelId("bad\nmodel"))
        assertNotNull(ProviderSetup.validateModelId("x".repeat(257)))
    }

    @Test fun codexUsesFixedEndpointAndOnlyRequiresName() {
        val result = ProviderSetup.validate("Codex", "not-a-url", "", ProviderKind.CODEX)
        assertTrue(result.isValid)
        assertEquals(CodexBackend.BASE_URL, result.normalizedBaseUrl)
        assertFalse(ProviderSetup.validate("", "", "", ProviderKind.CODEX).isValid)
    }
}
