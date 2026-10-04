package ovh.battistella.ondes.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Ondes brand palette — indigo "airwaves", matching the launcher-icon gradient
// (#3D5AFE → #1A237E). Cool sky/teal accents keep the whole system on a single
// blue-green family so the logo and the in-app theme read as one identity.
private val Indigo = Color(0xFF3D5AFE)        // logo primary — the signature accent
private val IndigoBright = Color(0xFF93A3FF)  // lighter indigo for dark surfaces
private val SkyLight = Color(0xFF0091EA)      // cool accent on light backgrounds
private val SkyDark = Color(0xFF6FD6FF)       // cool accent on dark backgrounds
private val TealLight = Color(0xFF00897B)     // "downloaded / saved offline"
private val TealDark = Color(0xFF4DD0C4)

private val DarkColors = darkColorScheme(
    primary = IndigoBright,
    onPrimary = Color(0xFF0A1A6B),
    primaryContainer = Color(0xFF283593),
    onPrimaryContainer = Color(0xFFDEE1FF),
    secondary = SkyDark,
    onSecondary = Color(0xFF00344A),
    secondaryContainer = Color(0xFF004C6B),
    onSecondaryContainer = Color(0xFFC5E7FF),
    tertiary = TealDark,
    onTertiary = Color(0xFF00382F),
    background = Color(0xFF111319),
    onBackground = Color(0xFFE4E2EC),
    surface = Color(0xFF15171F),
    onSurface = Color(0xFFE4E2EC),
    surfaceVariant = Color(0xFF2A2E45),
    onSurfaceVariant = Color(0xFFC6C8DA),
    outline = Color(0xFF8F909F),
)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDEE1FF),
    onPrimaryContainer = Color(0xFF00105C),
    secondary = SkyLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE6FF),
    onSecondaryContainer = Color(0xFF001E30),
    tertiary = TealLight,
    onTertiary = Color.White,
    background = Color(0xFFFAFBFF),
    onBackground = Color(0xFF1A1B22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1B22),
    surfaceVariant = Color(0xFFE3E5F4),
    onSurfaceVariant = Color(0xFF45475A),
    outline = Color(0xFF767889),
)

private val DarkOndesColors = OndesColors(
    brand = IndigoBright,
    onBrand = Color(0xFF0A1A6B),
    accent = SkyDark,
    played = IndigoBright,
    downloaded = TealDark,
)

private val LightOndesColors = OndesColors(
    brand = Indigo,
    onBrand = Color.White,
    accent = SkyLight,
    played = Indigo,
    downloaded = TealLight,
)

/**
 * Ondes' theme entry point. Wraps [MaterialExpressiveTheme] — which supplies
 * the Material 3 Expressive spring-based [MotionScheme] and component defaults —
 * and provides the brand token layer ([OndesColors], [OndesSpacing], [OndesShapes])
 * and type scale ([OndesTypography]) on top.
 *
 * Material You dynamic color is kept on by default; the indigo brand tokens stay
 * fixed so the identity matches the launcher icon regardless of the device wallpaper.
 */
@Composable
fun OndesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            // Keep Material You's wallpaper-derived palette, but pin the primary
            // role to the indigo brand so Material components (buttons, switches,
            // the nav indicator) match the launcher icon instead of showing a
            // second, competing accent.
            val dynamic = if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
            dynamic.copy(
                primary = if (darkTheme) IndigoBright else Indigo,
                onPrimary = if (darkTheme) Color(0xFF0A1A6B) else Color.White,
            )
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    val ondesColors = if (darkTheme) DarkOndesColors else LightOndesColors

    CompositionLocalProvider(
        LocalOndesColors provides ondesColors,
        LocalOndesSpacing provides OndesSpacing(),
        LocalOndesShapes provides OndesShapes(),
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            typography = OndesTypography,
            content = content,
        )
    }
}

/**
 * Accessor for Ondes tokens, mirroring how [MaterialTheme] exposes its subsystems
 * (e.g. `OndesTheme.colors.brand`). A function and an object can share a name in
 * Kotlin, so `OndesTheme { }` and `OndesTheme.colors` coexist.
 */
object OndesTheme {
    val colors: OndesColors
        @Composable @ReadOnlyComposable get() = LocalOndesColors.current
    val spacing: OndesSpacing
        @Composable @ReadOnlyComposable get() = LocalOndesSpacing.current
    val shapes: OndesShapes
        @Composable @ReadOnlyComposable get() = LocalOndesShapes.current
}
