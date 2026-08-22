package ovh.battistella.ondes.ui.screens.library

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ovh.battistella.ondes.common.SnackbarController
import ovh.battistella.ondes.data.local.OndesDatabase
import ovh.battistella.ondes.data.repository.PodcastRepository
import ovh.battistella.ondes.testing.TestSupport
import ovh.battistella.ondes.ui.theme.OndesTheme
import androidx.lifecycle.SavedStateHandle

@RunWith(RobolectricTestRunner::class)
class LibraryScreenTest {

    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val snackbar = SnackbarController()
    private lateinit var db: OndesDatabase
    private lateinit var repo: PodcastRepository

    private fun build(): LibraryViewModel {
        db = TestSupport.inMemoryDb()
        repo = TestSupport.repository(db, Dispatchers.Unconfined)
        return LibraryViewModel(SavedStateHandle(), repo, snackbar, context)
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun subscribedPodcastsAreListed() {
        val vm = build()
        runBlocking {
            db.podcastDao().upsert(TestSupport.podcast(feedUrl = "f1", title = "My Favourite Show"))
        }
        compose.setContent {
            OndesTheme(dynamicColor = false) {
                LibraryScreen(
                    onOpenPodcast = {},
                    onOpenPlayer = {},
                    onBrowse = {},
                    contentPadding = PaddingValues(0.dp),
                    viewModel = vm,
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("My Favourite Show").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("My Favourite Show").assertIsDisplayed()
    }
}
