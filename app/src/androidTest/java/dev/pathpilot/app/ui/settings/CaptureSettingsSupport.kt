package dev.pathpilot.app.ui.settings

import android.app.LocaleManager
import android.graphics.Bitmap
import android.os.Build
import android.os.LocaleList
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.pathpilot.app.runtime.AppStore
import dev.pathpilot.app.settings.AppLanguage
import dev.pathpilot.app.settings.AppPreferences
import java.io.File
import java.util.Locale

internal fun captureStore(): AppStore {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    check(context.packageName.endsWith(".capture"))
    return AppStore.get(context)
}

/** Sets the initial test language before MainActivity is launched; language tests then use the UI. */
internal fun prepareChineseCapture(store: AppStore = captureStore()) {
    store.setPreferences(AppPreferences(language = AppLanguage.ZH_CN))
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    if (Build.VERSION.SDK_INT >= 33) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList(Locale.SIMPLIFIED_CHINESE)
        }
    } else {
        context.getSharedPreferences("pathpilot-language", android.content.Context.MODE_PRIVATE)
            .edit().putString("language", AppLanguage.ZH_CN.name).commit()
    }
}

internal fun ComposeTestRule.captureSettingsScreenshot(name: String) {
    waitForIdle()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync()
    val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
    val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "settings-e2e")
    check(directory.isDirectory || directory.mkdirs())
    File(directory, "$name.png").outputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
    }
    bitmap.recycle()
}

internal fun closeSettingsKeyboard() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.runOnMainSync {
        val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single()
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
    }
    instrumentation.waitForIdleSync()
}
