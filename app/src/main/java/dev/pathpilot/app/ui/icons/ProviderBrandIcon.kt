package dev.pathpilot.app.ui.icons

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import dev.pathpilot.app.R
import dev.pathpilot.core.ProviderKind

/** Bundled MIT-licensed LobeHub vectors; provenance is packaged under assets/third_party. */
@Composable
fun ProviderBrandIcon(
    kind: ProviderKind,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    when (kind) {
        ProviderKind.CODEX -> Icon(
            painter = painterResource(R.drawable.ic_provider_openai),
            contentDescription = contentDescription,
            modifier = modifier,
            tint = MaterialTheme.colorScheme.onSurface,
        )
        ProviderKind.DEEPSEEK -> Icon(
            painter = painterResource(R.drawable.ic_provider_deepseek),
            contentDescription = contentDescription,
            modifier = modifier,
            tint = Color.Unspecified,
        )
        ProviderKind.OPENAI_COMPATIBLE -> Icon(
            imageVector = PathPilotIcons.Providers,
            contentDescription = contentDescription,
            modifier = modifier,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
