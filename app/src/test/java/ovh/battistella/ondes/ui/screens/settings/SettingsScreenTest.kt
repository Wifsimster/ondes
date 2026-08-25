package ovh.battistella.ondes.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ovh.battistella.ondes.R
import ovh.battistella.ondes.data.backup.BackupManager
import ovh.battistella.ondes.data.opml.OpmlManager
import ovh.battistella.ondes.data.settings.OndesSettings
import ovh.battistella.ondes.data.settings.SettingsRepository
import ovh.battistella.ondes.ui.theme.OndesTheme

@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {

    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun build(lastRefreshAt: Long = 0L): SettingsViewModel {
        val settingsRepository = mockk<SettingsRepository>(relaxed = true)
        every { settingsRepository.settings } returns flowOf(OndesSettings())
        every { settingsRepository.lastRefreshAt } returns flowOf(lastRefreshAt)
        return SettingsViewModel(
            context,
            settingsRepository,
            mockk<OpmlManager>(relaxed = true),
            mockk<BackupManager>(relaxed = true),
        )
    }

    @Test
    fun settingsControlsRender() {
        compose.setContent {
            OndesTheme(dynamicColor = false) {
                SettingsScreen(onBack = {}, contentPadding = PaddingValues(0.dp), viewModel = build())
            }
        }
        val autoplay = context.getString(R.string.autoplay_next_title)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(autoplay).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(autoplay).assertIsDisplayed()
    }

    /**
     * With background refresh on, Settings has to say when it last actually ran
     * and warn when the system is in a position to stop it — a schedule Android
     * has quietly parked otherwise looks identical to a working one.
     */
    @Test
    fun backgroundRefreshReportsItsHealth() {
        compose.setContent {
            OndesTheme(dynamicColor = false) {
                SettingsScreen(onBack = {}, contentPadding = PaddingValues(0.dp), viewModel = build())
            }
        }
        val lastChecked = context.getString(R.string.last_checked_title)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(lastChecked).fetchSemanticsNodes().isNotEmpty()
        }
        // Never run yet, and Robolectric's PowerManager reports the app as
        // battery-optimised, so the hint about it is offered.
        compose.onAllNodesWithText(context.getString(R.string.last_checked_never))
            .assertCountEquals(1)
        compose.onAllNodesWithText(context.getString(R.string.battery_limited_title))
            .assertCountEquals(1)
    }
}
