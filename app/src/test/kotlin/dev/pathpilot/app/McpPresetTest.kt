package dev.pathpilot.app

import org.junit.Assert.*
import org.junit.Test

class McpPresetTest {
    private fun preset(id: String) = McpPresets.all.single { it.id == id }

    @Test fun publicPresetDoesNotReuseAPreviousCredential() {
        val connection = preset("microsoft-learn").connection("token-from-another-form")

        assertEquals("", connection.bearerToken)
        assertEquals("https://learn.microsoft.com/api/mcp", connection.url)
        assertTrue(connection.enabled)
        assertNull(connection.allowedTools)
    }

    @Test fun requiredTokensAreTrimmedAndValidatedForBearerHeaders() {
        for (id in listOf("github", "context7")) {
            val preset = preset(id)
            assertTrue(preset.requiresToken)
            assertEquals("account-token", preset.connection("  account-token  ").bearerToken)
            assertThrows(IllegalArgumentException::class.java) { preset.connection("  ") }
            assertThrows(IllegalArgumentException::class.java) { preset.connection("token\nheader") }
        }
    }

    @Test fun oneEndpointCanKeepDifferentAccounts() {
        val preset = preset("github")
        val accountA = preset.connection("account-a")
        val accountB = preset.connection("account-b")

        assertNull(preset.existingConnection(listOf(accountA), "account-b"))
        assertSame(accountA, preset.existingConnection(listOf(accountA, accountB), " account-a "))
        assertSame(accountB, preset.existingConnection(listOf(accountA, accountB), "account-b"))
        assertNull(preset.existingConnection(listOf(accountA, accountB)))
        assertNotEquals(accountA.id, accountB.id)
    }

    @Test fun openingAnExistingConnectionPreservesItsSwitches() {
        val preset = preset("openai-docs")
        val connection = preset.connection().copy(enabled = false, allowedTools = setOf("search_docs"))

        val existing = preset.existingConnection(listOf(connection))
        assertSame(connection, existing)
        assertFalse(requireNotNull(existing).enabled)
        assertEquals(setOf("search_docs"), existing.allowedTools)
    }

    @Test fun aManuallyEnteredCanonicalAddressCanBeReused() {
        val preset = preset("openai-docs")
        val manual = preset.connection().copy(name = "My documentation", url = "https://DEVELOPERS.OPENAI.COM/mcp")

        assertSame(manual, preset.existingConnection(listOf(manual)))
        assertNull(preset.existingConnection(listOf(manual.copy(bearerToken = "other-credential"))))
        assertNull(preset.existingConnection(listOf(manual.copy(url = "https://developers.openai.com/another-mcp"))))
    }
}
