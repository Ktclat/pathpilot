package dev.pathpilot.app.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.pathpilot.app.R
import dev.pathpilot.app.ui.icons.PathPilotIcons

@Composable
internal fun SettingsAction(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(PathPilotIcons.Settings, stringResource(R.string.ui_settings))
    }
}
