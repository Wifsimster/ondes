package ovh.battistella.ondes.ui

import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class EdgeToEdgeTest {

    private fun activity(): ComponentActivity =
        Robolectric.buildActivity(ComponentActivity::class.java).create().get()

    @Test
    @Config(sdk = [30])
    fun `draws behind the system bars and into the cutout without contrast scrims`() {
        val activity = activity()

        activity.enableEdgeToEdgeCompat()

        val window = activity.window
        assertFalse(window.decorView.fitsSystemWindows)
        assertFalse(window.isStatusBarContrastEnforced)
        assertFalse(window.isNavigationBarContrastEnforced)
        assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS,
            window.attributes.layoutInDisplayCutoutMode,
        )
    }

    @Test
    @Config(sdk = [28])
    fun `pre-30 devices lay out behind the bars through the legacy visibility flags`() {
        val activity = activity()

        activity.enableEdgeToEdgeCompat()

        @Suppress("DEPRECATION")
        val flags = activity.window.decorView.systemUiVisibility
        @Suppress("DEPRECATION")
        val expected = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        assertEquals(expected, flags and expected)
    }

    @Test
    @Config(sdk = [28])
    fun `system bar icons follow the app theme, not the system one`() {
        val window = activity().window
        val controller = WindowCompat.getInsetsController(window, window.decorView)

        window.setSystemBarsAppearance(darkTheme = false)
        assertTrue(controller.isAppearanceLightStatusBars)
        assertTrue(controller.isAppearanceLightNavigationBars)

        window.setSystemBarsAppearance(darkTheme = true)
        assertFalse(controller.isAppearanceLightStatusBars)
        assertFalse(controller.isAppearanceLightNavigationBars)
    }
}
