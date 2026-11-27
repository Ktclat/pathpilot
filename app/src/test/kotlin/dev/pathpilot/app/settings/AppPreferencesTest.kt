package dev.pathpilot.app.settings

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPreferencesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun storedPreferencesWithoutPaletteKeepTheirExistingSelections() {
        val restored = json.decodeFromString<AppPreferences>(
            """{"themeMode":"DARK","dynamicColor":false,"language":"EN"}""",
        )
        assertEquals(ThemeMode.DARK, restored.themeMode)
        assertFalse(restored.dynamicColor)
        assertEquals(AppLanguage.EN, restored.language)
        assertEquals(ThemePalette.FOREST, restored.themePalette)
    }

    @Test fun legacyBackupPayloadRestoresItsPreferencesAndUsesTheOriginalPalette() {
        val restored = json.decodeFromString<BackupSnapshot>(
            """{
              "schemaVersion":1,
              "createdAtEpochMs":1795600000000,
              "config":{"accessKey":"sk-model-fixture-access-key-123456"},
              "preferences":{"themeMode":"LIGHT","dynamicColor":true,"language":"ZH_CN"},
              "mcp":{"accessKey":"sk-mcp-fixture-access-key-123456"}
            }""",
        )
        val normalized = BackupValidation.normalized(restored)
        assertEquals(ThemeMode.LIGHT, normalized.preferences.themeMode)
        assertTrue(normalized.preferences.dynamicColor)
        assertEquals(AppLanguage.ZH_CN, normalized.preferences.language)
        assertEquals(ThemePalette.FOREST, normalized.preferences.themePalette)
    }
}
