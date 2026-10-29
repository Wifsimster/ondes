package ovh.battistella.ondes.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class OndesColors(
    val brand: Color,
    val onBrand: Color,
    val accent: Color,
    val played: Color,
    val downloaded: Color,
)

@Immutable
data class OndesSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
)

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
