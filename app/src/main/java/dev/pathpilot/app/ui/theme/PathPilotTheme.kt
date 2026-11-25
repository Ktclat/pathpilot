package dev.pathpilot.app.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Complete fallback schemes for Android 8–11. Android 12+ uses the system's Monet palette.
private val LightColors = lightColorScheme(
    primary = Color(0xFF24543C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD4E8D8),
    onPrimaryContainer = Color(0xFF133721),
    inversePrimary = Color(0xFFA5D4B3),
    secondary = Color(0xFF526457),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD6E6D6),
    onSecondaryContainer = Color(0xFF24372A),
    tertiary = Color(0xFF4E6760),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD2E9E0),
    onTertiaryContainer = Color(0xFF193A30),
    background = Color(0xFFF7F9F4),
    onBackground = Color(0xFF182019),
    surface = Color(0xFFF7F9F4),
    onSurface = Color(0xFF182019),
    surfaceVariant = Color(0xFFE0E8DE),
    onSurfaceVariant = Color(0xFF465148),
    surfaceTint = Color(0xFF24543C),
    inverseSurface = Color(0xFF2D362F),
    inverseOnSurface = Color(0xFFEEF4EC),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF778279),
    outlineVariant = Color(0xFFC3CDC2),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF7F9F4),
    surfaceDim = Color(0xFFD6DED3),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F5EE),
    surfaceContainer = Color(0xFFEBF0E7),
    surfaceContainerHigh = Color(0xFFE5EAE1),
    surfaceContainerHighest = Color(0xFFDFE5DB),
    primaryFixed = Color(0xFFD4E8D8),
    primaryFixedDim = Color(0xFFA5D4B3),
    onPrimaryFixed = Color(0xFF133721),
    onPrimaryFixedVariant = Color(0xFF24543C),
    secondaryFixed = Color(0xFFD6E6D6),
    secondaryFixedDim = Color(0xFFBACCB9),
    onSecondaryFixed = Color(0xFF24372A),
    onSecondaryFixedVariant = Color(0xFF3D4C40),
    tertiaryFixed = Color(0xFFD2E9E0),
    tertiaryFixedDim = Color(0xFFAED0C3),
    onTertiaryFixed = Color(0xFF193A30),
    onTertiaryFixedVariant = Color(0xFF334E44),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA5D4B3),
    onPrimary = Color(0xFF073920),
    primaryContainer = Color(0xFF245039),
    onPrimaryContainer = Color(0xFFC1F0CC),
    inversePrimary = Color(0xFF24543C),
    secondary = Color(0xFFBACCB9),
    onSecondary = Color(0xFF26352A),
    secondaryContainer = Color(0xFF3D4C40),
    onSecondaryContainer = Color(0xFFD6E8D5),
    tertiary = Color(0xFFAED0C3),
    onTertiary = Color(0xFF1B372E),
    tertiaryContainer = Color(0xFF334E44),
    onTertiaryContainer = Color(0xFFCAECDD),
    background = Color(0xFF101811),
    onBackground = Color(0xFFDFE9DC),
    surface = Color(0xFF101811),
    onSurface = Color(0xFFDFE9DC),
    surfaceVariant = Color(0xFF3E4A40),
    onSurfaceVariant = Color(0xFFBECABC),
    surfaceTint = Color(0xFFA5D4B3),
    inverseSurface = Color(0xFFDFE9DC),
    inverseOnSurface = Color(0xFF273228),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF89968A),
    outlineVariant = Color(0xFF3E4A40),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF344036),
    surfaceDim = Color(0xFF101811),
    surfaceContainerLowest = Color(0xFF0B120D),
    surfaceContainerLow = Color(0xFF172018),
    surfaceContainer = Color(0xFF1B241C),
    surfaceContainerHigh = Color(0xFF252F26),
    surfaceContainerHighest = Color(0xFF303A31),
    primaryFixed = Color(0xFFD4E8D8),
    primaryFixedDim = Color(0xFFA5D4B3),
    onPrimaryFixed = Color(0xFF133721),
    onPrimaryFixedVariant = Color(0xFF24543C),
    secondaryFixed = Color(0xFFD6E6D6),
    secondaryFixedDim = Color(0xFFBACCB9),
    onSecondaryFixed = Color(0xFF24372A),
    onSecondaryFixedVariant = Color(0xFF3D4C40),
    tertiaryFixed = Color(0xFFD2E9E0),
    tertiaryFixedDim = Color(0xFFAED0C3),
    onTertiaryFixed = Color(0xFF193A30),
    onTertiaryFixedVariant = Color(0xFF334E44),
)

/** Follow the native system palette and theme; only the Activity owns the secure-window policy. */
@Composable
fun PathPilotTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            view.context.findActivity()?.let { activity ->
                val bars = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                // Android 8.0 cannot reliably show dark navigation icons. Preserve its white
                // controls on a black scrim; Android 8.1+ follows the theme transparently.
                val navigationBars = if (Build.VERSION.SDK_INT < 27) SystemBarStyle.dark(AndroidColor.BLACK) else bars
                activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = navigationBars)
                WindowCompat.getInsetsController(activity.window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark && Build.VERSION.SDK_INT >= 27
                }
                if (Build.VERSION.SDK_INT >= 29) activity.window.isNavigationBarContrastEnforced = false
            }
        }
    }
    MaterialExpressiveTheme(
        colorScheme = colors,
        typography = PathPilotTypography,
        content = content,
    )
}

private fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
