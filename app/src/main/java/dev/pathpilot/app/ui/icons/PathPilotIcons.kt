package dev.pathpilot.app.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Api
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Outlined Material interface icons. Provider brand marks live in ProviderBrandIcon. */
object PathPilotIcons {
    val Share: ImageVector get() = Icons.Outlined.WifiTethering
    val Providers: ImageVector get() = Icons.Outlined.Dns
    val Routes: ImageVector get() = Icons.AutoMirrored.Outlined.AltRoute
    val Settings: ImageVector get() = Icons.Outlined.Tune
    val Add: ImageVector get() = Icons.Outlined.Add
    val Back: ImageVector get() = Icons.AutoMirrored.Outlined.ArrowBack
    val Close: ImageVector get() = Icons.Outlined.Close
    val Edit: ImageVector get() = Icons.Outlined.Edit
    val Delete: ImageVector get() = Icons.Outlined.Delete
    val Copy: ImageVector get() = Icons.Outlined.ContentCopy
    val Visibility: ImageVector get() = Icons.Outlined.Visibility
    val VisibilityOff: ImageVector get() = Icons.Outlined.VisibilityOff
    val Play: ImageVector get() = Icons.Outlined.PlayArrow
    val Stop: ImageVector get() = Icons.Outlined.Stop
    val Link: ImageVector get() = Icons.Outlined.Link
    val Check: ImageVector get() = Icons.Outlined.CheckCircle
    val Error: ImageVector get() = Icons.Outlined.ErrorOutline
    val Key: ImageVector get() = Icons.Outlined.Key
    val Refresh: ImageVector get() = Icons.Outlined.Refresh
    val Usage: ImageVector get() = Icons.Outlined.QueryStats
    val OpenInNew: ImageVector get() = Icons.AutoMirrored.Outlined.OpenInNew
    val Account: ImageVector get() = Icons.Outlined.AccountCircle
    val Api: ImageVector get() = Icons.Outlined.Api
    val ExpandMore: ImageVector get() = Icons.Outlined.ExpandMore

    // The P-shaped loop and its inner route endpoint also appear in every launcher/notification
    // resource. Keep this geometry in sync with ic_pathpilot_mark.xml (24 x 24 viewport).
    val Brand: ImageVector by lazy {
        ImageVector.Builder("PathPilot", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(6f, 20f)
                lineTo(6f, 7f)
                curveTo(6f, 5.34f, 7.34f, 4f, 9f, 4f)
                lineTo(13f, 4f)
                curveTo(16.31f, 4f, 19f, 6.69f, 19f, 10f)
                curveTo(19f, 13.31f, 16.31f, 16f, 13f, 16f)
                lineTo(6f, 16f)
                moveTo(6f, 10f)
                lineTo(12f, 10f)
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(13.5f, 10f)
                arcToRelative(1.5f, 1.5f, 0f, true, true, -3f, 0f)
                arcToRelative(1.5f, 1.5f, 0f, true, true, 3f, 0f)
                close()
                moveTo(7.5f, 20f)
                arcToRelative(1.5f, 1.5f, 0f, true, true, -3f, 0f)
                arcToRelative(1.5f, 1.5f, 0f, true, true, 3f, 0f)
                close()
            }
        }.build()
    }
}
