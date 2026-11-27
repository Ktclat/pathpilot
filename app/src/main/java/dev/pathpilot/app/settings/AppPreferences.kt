package dev.pathpilot.app.settings

import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
enum class AppLanguage { SYSTEM, ZH_CN, EN }

@Serializable
enum class ThemePalette { FOREST, OCEAN, VIOLET, ROSE, AMBER }

@Serializable
data class AppPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val themePalette: ThemePalette = ThemePalette.FOREST,
)
