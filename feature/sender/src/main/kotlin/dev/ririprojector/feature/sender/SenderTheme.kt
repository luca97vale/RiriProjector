package dev.ririprojector.feature.sender

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ririprojector.core.ui.R as UiR

/** Brand colors Material's scheme has no slot for (from `:core:ui`). */
@Immutable
internal data class RiriExtraColors(
    val tile: Color,
    val cyanLight: Color,
    val tan: Color,
    val warnContainer: Color,
    val onWarnContainer: Color,
    val errorContainer: Color,
    val ok: Color,
)

internal val LocalRiriColors =
    staticCompositionLocalOf {
        RiriExtraColors(
            Color.Unspecified,
            Color.Unspecified,
            Color.Unspecified,
            Color.Unspecified,
            Color.Unspecified,
            Color.Unspecified,
            Color.Unspecified,
        )
    }

/**
 * The RiriProjector look: always dark, navy ground and projector-beam cyan, matching the launcher
 * artwork and the receiver screen (one brand on every device, so no light variant).
 */
@Composable
internal fun RiriTheme(content: @Composable () -> Unit) {
    val scheme =
        darkColorScheme(
            primary = colorResource(UiR.color.riri_cyan),
            onPrimary = colorResource(UiR.color.riri_on_cyan),
            primaryContainer = colorResource(UiR.color.riri_tile),
            onPrimaryContainer = colorResource(UiR.color.riri_cyan_light),
            secondary = colorResource(UiR.color.riri_cyan_light),
            onSecondary = colorResource(UiR.color.riri_on_cyan),
            secondaryContainer = colorResource(UiR.color.riri_tile),
            onSecondaryContainer = colorResource(UiR.color.riri_on_surface),
            tertiary = colorResource(UiR.color.riri_tan),
            background = colorResource(UiR.color.riri_ink),
            onBackground = colorResource(UiR.color.riri_on_surface),
            surface = colorResource(UiR.color.riri_ink),
            onSurface = colorResource(UiR.color.riri_on_surface),
            surfaceVariant = colorResource(UiR.color.riri_surface),
            onSurfaceVariant = colorResource(UiR.color.riri_muted),
            surfaceContainerLowest = colorResource(UiR.color.riri_ink),
            surfaceContainerLow = colorResource(UiR.color.riri_navy),
            surfaceContainer = colorResource(UiR.color.riri_surface),
            surfaceContainerHigh = colorResource(UiR.color.riri_surface_high),
            surfaceContainerHighest = colorResource(UiR.color.riri_tile),
            outline = colorResource(UiR.color.riri_outline),
            outlineVariant = colorResource(UiR.color.riri_outline_variant),
            error = colorResource(UiR.color.riri_coral),
            onError = colorResource(UiR.color.riri_on_coral),
            errorContainer = colorResource(UiR.color.riri_error_container),
            onErrorContainer = colorResource(UiR.color.riri_on_surface),
        )
    val extra =
        RiriExtraColors(
            tile = colorResource(UiR.color.riri_tile),
            cyanLight = colorResource(UiR.color.riri_cyan_light),
            tan = colorResource(UiR.color.riri_tan),
            warnContainer = colorResource(UiR.color.riri_warn_container),
            onWarnContainer = colorResource(UiR.color.riri_on_warn_container),
            errorContainer = colorResource(UiR.color.riri_error_container),
            ok = colorResource(UiR.color.riri_ok),
        )
    val base = Typography()
    val typography =
        base.copy(
            headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 26.sp),
            headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
        )
    val shapes =
        Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(28.dp),
        )
    androidx.compose.runtime.CompositionLocalProvider(LocalRiriColors provides extra) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
    }
}
