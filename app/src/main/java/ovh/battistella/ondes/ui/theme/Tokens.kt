package ovh.battistella.ondes.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Ondes' design-system tokens — the named values the UI is built from.
 *
 * Material 3 (via [OndesTheme]) still owns the base color scheme, type scale and
 * motion; the brand fonts live in Type.kt. These three token sets layer the *brand* on top: a fixed indigo
 * identity (matching the launcher icon), a consistent spacing rhythm and a shared
 * shape vocabulary, so a change here ripples to every screen instead of being
 * re-tuned per call site.
 */

/** Brand-specific semantic colors that Material's [androidx.compose.material3.ColorScheme] doesn't model. */
@Immutable
data class OndesColors(
    /** The indigo signature accent — stays constant even under Material You dynamic color. */
    val brand: Color,
    val onBrand: Color,
    /** Cool secondary accent used for highlights (e.g. the download spinner). */
    val accent: Color,
    /** "Played / finished" affordance tint. */
    val played: Color,
    /** "Downloaded for offline" affordance tint. */
    val downloaded: Color,
)

/** Consistent spacing scale (4dp rhythm). Use these instead of raw dp paddings. */
@Immutable
data class OndesSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
)

/** Shared shape vocabulary for artwork and surfaces. */
@Immutable
data class OndesShapes(
    val artworkSmall: RoundedCornerShape = RoundedCornerShape(8.dp),
    val artworkMedium: RoundedCornerShape = RoundedCornerShape(12.dp),
    val artworkLarge: RoundedCornerShape = RoundedCornerShape(24.dp),
)

internal val LocalOndesColors = staticCompositionLocalOf<OndesColors> {
    error("OndesColors not provided — wrap content in OndesTheme { }")
}
internal val LocalOndesSpacing = staticCompositionLocalOf { OndesSpacing() }
internal val LocalOndesShapes = staticCompositionLocalOf { OndesShapes() }
