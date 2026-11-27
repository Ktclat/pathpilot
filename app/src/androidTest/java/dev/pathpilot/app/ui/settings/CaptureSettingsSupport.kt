package dev.pathpilot.app.ui.settings

import android.app.LocaleManager
import android.graphics.Bitmap
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
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

internal fun ComposeTestRule.captureSettingsScreenshot(name: String, internalStorage: Boolean = false) {
    waitForIdle()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync()
    val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
    val context = instrumentation.targetContext
    val directory = File(if (internalStorage) context.filesDir else context.getExternalFilesDir(null), "settings-e2e")
    check(directory.isDirectory || directory.mkdirs())
    File(directory, "$name.png").outputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
    }
    bitmap.recycle()
}

/** The API 35 capture device also has separate Dialog and BottomSheet input windows. */
internal fun hideCaptureKeyboard() {
    check(Build.VERSION.SDK_INT >= 29)
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    lateinit var focusedRoot: View
    instrumentation.runOnMainSync {
        focusedRoot = WindowInspector.getGlobalWindowViews().single { it.hasWindowFocus() }
        focusedRoot.context.getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(focusedRoot.windowToken, 0)
    }
    val deadline = SystemClock.uptimeMillis() + 10_000
    while (SystemClock.uptimeMillis() < deadline) {
        var visible = false
        instrumentation.runOnMainSync {
            visible = ViewCompat.getRootWindowInsets(focusedRoot)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        if (!visible) return
        SystemClock.sleep(50)
    }
    error("IME did not close for the focused input window")
}
