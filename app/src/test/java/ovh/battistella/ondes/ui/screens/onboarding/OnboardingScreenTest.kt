package ovh.battistella.ondes.ui.screens.onboarding

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ovh.battistella.ondes.R
import ovh.battistella.ondes.data.local.OndesDatabase
import ovh.battistella.ondes.data.repository.PodcastRepository
import ovh.battistella.ondes.data.settings.SettingsRepository
import ovh.battistella.ondes.testing.TestSupport
import ovh.battistella.ondes.ui.theme.OndesTheme
import ovh.battistella.ondes.common.SnackbarController

@RunWith(RobolectricTestRunner::class)
class OnboardingScreenTest {

    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: OndesDatabase
    private lateinit var repo: PodcastRepository

    private fun build(): OnboardingViewModel {
        db = TestSupport.inMemoryDb()
        repo = TestSupport.repository(db, Dispatchers.Unconfined)
        return OnboardingViewModel(context, repo, mockk<SettingsRepository>(relaxed = true), SnackbarController())
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun themePickerRendersTitle() {
        compose.setContent {
            OndesTheme(dynamicColor = false) {
                OnboardingScreen(viewModel = build())
            }
        }
        val title = context.getString(R.string.onboarding_title)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(title).assertIsDisplayed()
    }
}
