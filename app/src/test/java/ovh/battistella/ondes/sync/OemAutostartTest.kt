package ovh.battistella.ondes.sync

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OemAutostartTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun supportedOnlyOnKnownManufacturers() {
        listOf("Xiaomi", "REDMI", "POCO", "HUAWEI", "HONOR", "OPPO", "realme", "OnePlus", "vivo")
            .forEach { assertTrue(it, OemAutostart.isSupported(it)) }
        listOf("Google", "samsung", "unknown", "").forEach {
            assertFalse(it, OemAutostart.isSupported(it))
        }
    }

    @Test
    fun xiaomiTargetsMiuiAutostartThenFallsBackToAppDetails() {
        val intents = OemAutostart.candidateIntents(context, "Xiaomi")
        assertEquals(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
            intents.first().component,
        )
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intents.last().action)
        assertEquals("package:${context.packageName}", intents.last().dataString)
    }

    @Test
    fun otherManufacturersOnlyGetAppDetails() {
        val intents = OemAutostart.candidateIntents(context, "Google")
        assertEquals(1, intents.size)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intents.single().action)
    }

    @Test
    fun resolvePrefersInstalledOemScreen() {
        val resolved = OemAutostart.resolve(context, "Redmi") { true }
        assertEquals("com.miui.securitycenter", resolved!!.component?.packageName)
    }

    @Test
    fun resolveFallsBackToAppDetailsWhenOemScreenMissing() {
        val resolved = OemAutostart.resolve(context, "Xiaomi") { it.component == null }
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, resolved!!.action)
    }

    @Test
    fun resolveReturnsNullWhenNothingCanHandleIt() {
        assertNull(OemAutostart.resolve(context, "Xiaomi") { false })
    }

    @Test
    fun everyOemPackageIsDeclaredForPackageVisibility() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        OemAutostart.queriedPackages.forEach {
            assertTrue(it, manifest.contains("android:name=\"$it\""))
        }
    }

    @Test
    fun staleOnlyWhenEnabledAndPastThreshold() {
        val hour = 60L * 60 * 1000
        val now = 100L * hour
        val installed = now - 50L * hour
        assertFalse(OemAutostart.isStale(true, now - 5L * hour, installed, now))
        assertTrue(OemAutostart.isStale(true, now - OemAutostart.STALE_AFTER_MS - 1, installed, now))
        assertFalse(OemAutostart.isStale(false, now - 10L * hour, installed, now))
    }

    @Test
    fun neverRefreshedFallsBackToInstallTime() {
        val hour = 60L * 60 * 1000
        val now = 100L * hour
        assertTrue(OemAutostart.isStale(true, 0L, now - 7L * hour, now))
        assertFalse(OemAutostart.isStale(true, 0L, now - 5L * hour, now))
        assertFalse(OemAutostart.isStale(false, 0L, now - 7L * hour, now))
        assertFalse(OemAutostart.isStale(true, 0L, 0L, now))
    }

    @Test
    fun installedAtReadsPackageInfo() {
        assertTrue(OemAutostart.installedAt(context) >= 0L)
    }
}
