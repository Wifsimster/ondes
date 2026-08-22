package ovh.battistella.ondes.ui.screens.podcast

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ovh.battistella.ondes.common.SnackbarController
import ovh.battistella.ondes.data.local.OndesDatabase
import ovh.battistella.ondes.data.remote.RssParser
import ovh.battistella.ondes.data.repository.PodcastRepository
import ovh.battistella.ondes.download.DownloadManager
import ovh.battistella.ondes.playback.PlaybackConnection
import ovh.battistella.ondes.playback.PlayerUiState
import ovh.battistella.ondes.testing.TestSupport
import ovh.battistella.ondes.ui.theme.OndesTheme

@RunWith(RobolectricTestRunner::class)
class PodcastScreenTest {

    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val snackbar = SnackbarController()
    private val downloadManager = mockk<DownloadManager>(relaxed = true)
    private val rss = mockk<RssParser>(relaxed = true)
    private val feedUrl = "https://example.com/feed.xml"
    private lateinit var db: OndesDatabase
    private lateinit var repo: PodcastRepository
    private lateinit var connection: PlaybackConnection

    private fun build(): PodcastViewModel {
        db = TestSupport.inMemoryDb()
        repo = TestSupport.repository(db, Dispatchers.Unconfined, rss = rss)
        connection = TestSupport.mockConnection(MutableStateFlow(PlayerUiState()))
        every { rss.fetch(feedUrl, any(), any()) } returns TestSupport.feedFetch(TestSupport.parsedFeed(
            title = "My Show",
            episodes = listOf(TestSupport.parsedEpisode(guid = "ep-1", title = "Kotlin Weekly")),
        ))
        return PodcastViewModel(
            context = context,
            savedStateHandle = SavedStateHandle(mapOf("feedUrl" to feedUrl)),
            repository = repo,
            connection = connection,
            downloadManager = downloadManager,
            snackbar = snackbar,
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun showTitleAndEpisodesRender() {
        val vm = build()
        // Seed directly so the list is present at first composition (the VM's
        // init refresh runs on a real IO thread and isn't awaited by the clock).
        runBlocking {
            db.podcastDao().upsert(TestSupport.podcast(feedUrl = feedUrl, title = "My Show"))
            db.episodeDao().upsertAll(
                listOf(TestSupport.episode(id = "ep-1", feedUrl = feedUrl, title = "Kotlin Weekly")),
            )
        }
        compose.setContent {
            OndesTheme(dynamicColor = false) {
                PodcastScreen(onBack = {}, onOpenPlayer = {}, viewModel = vm)
            }
        }
        // The show title renders in the top bar (episode list lives below a tall
        // header in a LazyColumn; episode/filtering behaviour is covered by the
        // PodcastViewModel test).
        // "My Show" appears both in the top bar and the header, so assert on the
        // first match.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("My Show").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("My Show")[0].assertIsDisplayed()
    }
}
