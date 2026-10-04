package ovh.battistella.ondes.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import ovh.battistella.ondes.R

// Ondes brand type (decided 2026-10-04): Space Grotesk for display — titles,
// headlines, numbers — and Manrope for UI and body text. Both SIL OFL 1.1; see
// docs/fonts/. Only the weights the app uses ship, as static Latin subsets.

/** Display face: wordmark, screen titles, headlines. */
val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)

/** UI face: body copy, labels, buttons. */
val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
)

private val Base = Typography()

/**
 * Material 3 type scale with the brand faces swapped in. Sizes, line heights and
 * weights stay Material's defaults; only the families change.
 */
val OndesTypography = Typography(
    displayLarge = Base.displayLarge.copy(fontFamily = SpaceGrotesk),
    displayMedium = Base.displayMedium.copy(fontFamily = SpaceGrotesk),
    displaySmall = Base.displaySmall.copy(fontFamily = SpaceGrotesk),
    headlineLarge = Base.headlineLarge.copy(fontFamily = SpaceGrotesk),
    headlineMedium = Base.headlineMedium.copy(fontFamily = SpaceGrotesk),
    headlineSmall = Base.headlineSmall.copy(fontFamily = SpaceGrotesk),
    titleLarge = Base.titleLarge.copy(fontFamily = SpaceGrotesk),
    titleMedium = Base.titleMedium.copy(fontFamily = SpaceGrotesk),
    titleSmall = Base.titleSmall.copy(fontFamily = SpaceGrotesk),
    bodyLarge = Base.bodyLarge.copy(fontFamily = Manrope),
    bodyMedium = Base.bodyMedium.copy(fontFamily = Manrope),
    bodySmall = Base.bodySmall.copy(fontFamily = Manrope),
    labelLarge = Base.labelLarge.copy(fontFamily = Manrope),
    labelMedium = Base.labelMedium.copy(fontFamily = Manrope),
    labelSmall = Base.labelSmall.copy(fontFamily = Manrope),
)

/**
 * Tabular (fixed-width) figures, so a ticking duration such as "12:34" keeps its
 * width instead of jittering as the digits change.
 */
fun TextStyle.tabularNums(): TextStyle = copy(fontFeatureSettings = "tnum")
