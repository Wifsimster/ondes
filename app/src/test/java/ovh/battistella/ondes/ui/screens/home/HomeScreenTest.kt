package ovh.battistella.ondes.ui.screens.home

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ovh.battistella.ondes.R
import ovh.battistella.ondes.data.local.OndesDatabase
import ovh.battistella.ondes.data.repository.PodcastRepository
import ovh.battistella.ondes.download.DownloadManager
import ovh.battistella.ondes.playback.PlaybackConnection
import ovh.battistella.ondes.playback.PlayerUiState
import ovh.battistella.ondes.testing.TestSupport
import ovh.battistella.ondes.ui.theme.OndesTheme
import ovh.battistella.ondes.common.SnackbarController

@RunWith(RobolectricTestRunner::class)
class HomeScreenTest {

    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val snackbar = SnackbarController()
    private val downloadManager = mockk<DownloadManager>(relaxed = true)
    private lateinit var db: OndesDatabase
    private lateinit var repo: PodcastRepository
    private lateinit var connection: PlaybackConnection

    private fun build(): HomeViewModel {
        db = TestSupport.inMemoryDb()
        repo = TestSupport.repository(db, Dispatchers.Unconfined)
        connection = TestSupport.mockConnection(MutableStateFlow(PlayerUiState()))
        return HomeViewModel(context, repo, connection, downloadManager, snackbar)
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    private fun setScreen(vm: HomeViewModel) = compose.setContent {
        OndesTheme(dynamicColor = false) {
            HomeScreen(
                onOpenPlayer = {},
                onBrowse = {},
                onOpenSettings = {},
                contentPadding = PaddingValues(0.dp),
                viewModel = vm,
            )
        }
    }

    @Test
    fun emptyLibraryShowsWelcome() {
        setScreen(build())
        val welcome = context.getString(R.string.home_welcome_title)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(welcome).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(welcome).assertIsDisplayed()
    }

    @Test
    fun latestEpisodesAreListed() {
        val vm = build()
        runBlocking {
            db.podcastDao().upsert(TestSupport.podcast(feedUrl = "f1", subscribed = true))
            db.episodeDao().upsertAll(
                listOf(TestSupport.episode(id = "e1", feedUrl = "f1", title = "Fresh Episode", pubDate = 99)),
            )
        }
        setScreen(vm)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Fresh Episode").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Fresh Episode").assertIsDisplayed()
    }
}
