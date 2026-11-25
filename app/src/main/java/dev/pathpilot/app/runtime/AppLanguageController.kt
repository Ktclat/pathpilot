package dev.pathpilot.app.runtime

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import dev.pathpilot.app.settings.AppLanguage
import java.util.Locale

/** The cache supplies Android 8–12 with the locale before an Activity inflates its resources. */
object AppLanguageController {
    private const val PREFS = "pathpilot-language"
    private const val KEY = "language"

    fun current(context: Context): AppLanguage {
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (locales.isEmpty) return AppLanguage.SYSTEM
            return when (locales[0].language) {
                "zh" -> AppLanguage.ZH_CN
                "en" -> AppLanguage.EN
                else -> AppLanguage.SYSTEM
            }
        }
        return cachedSelection(context)
    }

    private fun cachedSelection(context: Context): AppLanguage = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY, null)?.let { saved -> AppLanguage.entries.firstOrNull { it.name == saved } }
        ?: AppLanguage.SYSTEM

    fun wrapContext(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val locale = locale(current(base)) ?: return base
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        })
    }

    fun apply(activity: Activity, language: AppLanguage) {
        val previous = cachedSelection(activity)
        if (previous != language) activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, language.name).apply()
        if (Build.VERSION.SDK_INT >= 33) {
            val manager = activity.getSystemService(LocaleManager::class.java)
            val desired = locale(language)?.let { LocaleList(it) } ?: LocaleList.getEmptyLocaleList()
            if (manager.applicationLocales != desired) manager.applicationLocales = desired
        } else if (previous != language) {
            activity.recreate()
        }
    }

    private fun locale(language: AppLanguage): Locale? = when (language) {
        AppLanguage.SYSTEM -> null
        AppLanguage.ZH_CN -> Locale.SIMPLIFIED_CHINESE
        AppLanguage.EN -> Locale.ENGLISH
    }
}
