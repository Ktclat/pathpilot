package dev.pathpilot.app.ui.theme

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.pathpilot.app.R
import dev.pathpilot.app.settings.AppPreferences
import dev.pathpilot.app.settings.ThemeMode
import dev.pathpilot.app.settings.ThemePalette
import dev.pathpilot.app.ui.icons.PathPilotIcons

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ThemeOptionsSheet(
    preferences: AppPreferences,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onChange: (AppPreferences) -> Unit,
) {
    val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val dynamicSelected = dynamicSupported && preferences.dynamicColor
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.settings_appearance), style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.theme_done)) }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().testTag("theme-options-scroll"),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.theme_mode), style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ThemeMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = preferences.themeMode == mode,
                                    enabled = enabled,
                                    onClick = { onChange(preferences.copy(themeMode = mode)) },
                                    label = { Text(themeModeLabel(mode)) },
                                )
                            }
                        }
                    }
                }
                item {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        ListItem(
                            modifier = Modifier.toggleable(dynamicSelected,
                                enabled = enabled && dynamicSupported, role = Role.Switch,
                                onValueChange = { onChange(preferences.copy(dynamicColor = it)) }),
                            content = { Text(stringResource(R.string.settings_dynamic_color)) },
                            supportingContent = if (dynamicSupported) null else {
                                { Text(stringResource(R.string.settings_dynamic_unavailable)) }
                            },
                            trailingContent = { Switch(dynamicSelected, onCheckedChange = null, enabled = enabled && dynamicSupported) },
                            enabled = enabled && dynamicSupported,
                        )
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.theme_palette), style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                            maxItemsInEachRow = 2) {
                            ThemePalette.entries.forEach { palette ->
                                PaletteOption(palette = palette, dark = dark,
                                    selected = !dynamicSelected && preferences.themePalette == palette,
                                    enabled = enabled, modifier = Modifier.weight(1f),
                                    onClick = { onChange(preferences.copy(dynamicColor = false, themePalette = palette)) })
                            }
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteOption(palette: ThemePalette, dark: Boolean, selected: Boolean, enabled: Boolean,
                          modifier: Modifier, onClick: () -> Unit) {
    val preview = presetColorScheme(palette, dark)
    Surface(
        modifier = modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(preview.primary, preview.secondary, preview.tertiary).forEach { color ->
                        Box(Modifier.size(20.dp).background(color, CircleShape))
                    }
                }
                if (selected) Icon(PathPilotIcons.Check, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                else Spacer(Modifier.size(20.dp))
            }
            Text(themePaletteLabel(palette), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun themeSummary(preferences: AppPreferences): String = stringResource(
    R.string.theme_summary,
    themeModeLabel(preferences.themeMode),
    if (preferences.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        stringResource(R.string.settings_dynamic_color) else themePaletteLabel(preferences.themePalette),
)

@Composable
private fun themeModeLabel(mode: ThemeMode): String = stringResource(when (mode) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
})

@Composable
private fun themePaletteLabel(palette: ThemePalette): String = stringResource(when (palette) {
    ThemePalette.FOREST -> R.string.theme_palette_forest
    ThemePalette.OCEAN -> R.string.theme_palette_ocean
    ThemePalette.VIOLET -> R.string.theme_palette_violet
    ThemePalette.ROSE -> R.string.theme_palette_rose
    ThemePalette.AMBER -> R.string.theme_palette_amber
})
