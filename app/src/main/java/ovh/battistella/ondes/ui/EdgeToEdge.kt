package ovh.battistella.ondes.ui

import android.content.res.Configuration
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat

/**
 * Edge-to-edge without androidx.activity's `enableEdgeToEdge()`.
 *
 * `enableEdgeToEdge()` is built from per-API shims (`EdgeToEdgeApi23`…`Api35`)
 * that call `Window.setStatusBarColor` / `setNavigationBarColor` and write
 * `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES` — all three deprecated in
 * Android 15 and flagged by Play Console on every release. The shims are
 * still shipped in activity 1.13.0, so the only way to clear the warning is
 * to not call it.
 *
 * The transparent bar colours already come from `Theme.Ondes` (theme
 * attributes, not API calls), so at runtime this only has to stop the decor
 * view from consuming the insets, drop the API 29+ contrast scrims and let
 * the window draw into the display cutout. Behaviour matches
 * `enableEdgeToEdge()` on every API level the app supports (26+), except that
 * API 28–29 keep the default cutout mode: `SHORT_EDGES` is the deprecated
 * constant, and the default already draws behind a cutout that sits inside
 * the (transparent) status bar, i.e. every phone in portrait.
 */
fun ComponentActivity.enableEdgeToEdgeCompat() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val params = window.attributes
        params.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        window.attributes = params
    }
    // Same starting point as enableEdgeToEdge(): follow the system until the
    // app theme is known, then MainActivity re-applies it from the real theme.
    val systemDark = resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    window.setSystemBarsAppearance(darkTheme = systemDark)
}

/**
 * Dark icons on a light theme, light icons on a dark one. Driven by the
 * app's own [ovh.battistella.ondes.data.settings.ThemeMode] rather than the
 * system setting, so a forced light theme on a dark system (or the reverse)
 * still gets readable status and navigation bar icons.
 */
fun Window.setSystemBarsAppearance(darkTheme: Boolean) {
    WindowCompat.getInsetsController(this, decorView).apply {
        isAppearanceLightStatusBars = !darkTheme
        isAppearanceLightNavigationBars = !darkTheme
    }
}
