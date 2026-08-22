package ovh.battistella.ondes.ui.screens.podcast

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import ovh.battistella.ondes.testing.MainDispatcherRule
import ovh.battistella.ondes.testing.TestSupport

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PodcastViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val snackbar = SnackbarController()
    private val downloadManager = mockk<DownloadManager>(relaxed = true)
    private val rss = mockk<RssParser>(relaxed = true)
    private val playerFlow = MutableStateFlow(PlayerUiState())
    private val feedUrl = "https://example.com/feed.xml"
    private lateinit var db: OndesDatabase
    private lateinit var repo: PodcastRepository
    private lateinit var connection: PlaybackConnection

    private fun build(): PodcastViewModel {
        db = TestSupport.inMemoryDb()
        repo = TestSupport.repository(db, mainDispatcher.dispatcher, rss = rss)
        connection = TestSupport.mockConnection(playerFlow)
        // The screen's init refreshes the feed; feed it two episodes.
        every { rss.fetchAndParse(feedUrl) } returns TestSupport.parsedFeed(
            title = "My Show",
            episodes = listOf(
                TestSupport.parsedEpisode(guid = "ep-1", title = "Kotlin Weekly", pubDate = 2),
                TestSupport.parsedEpisode(guid = "ep-2", title = "Scala Times", pubDate = 1),
            ),
        )
        return PodcastViewModel(
            context = context,
            savedStateHandle = SavedStateHandle(mapOf("feedUrl" to feedUrl)),
            repository = repo,
            connection = connection,
            downloadManager = downloadManager,
            snackbar = snackbar,
        )
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun `init refresh loads the feed's episodes newest-first`() = runTest(mainDispatcher.dispatcher) {
        val vm = build()
        backgroundScope.launch { vm.episodes.collect {} }
        advanceUntilIdle()

        assertEquals(listOf("ep-1", "ep-2"), vm.episodes.value.map { it.id })
        assertEquals("My Show", db.podcastDao().getPodcast(feedUrl)?.title)
    }

    @Test
    fun `the title search box filters episodes`() = runTest(mainDispatcher.dispatcher) {
        val vm = build()
        backgroundScope.launch { vm.filteredEpisodes.collect {} }
        advanceUntilIdle()

        vm.onQueryChange("kotlin")
        advanceUntilIdle()

        assertEquals(listOf("ep-1"), vm.filteredEpisodes.value.map { it.id })
    }

    @Test
    fun `unplayed-only hides finished episodes`() = runTest(mainDispatcher.dispatcher) {
        val vm = build()
        backgroundScope.launch { vm.filteredEpisodes.collect {} }
        advanceUntilIdle()
        db.episodeDao().setPlayed("ep-2", true)
        advanceUntilIdle()

        vm.toggleUnplayedOnly()
        advanceUntilIdle()

        assertEquals(listOf("ep-1"), vm.filteredEpisodes.value.map { it.id })
    }

    @Test
    fun `toggleSubscribe unsubscribes a subscribed show`() = runTest(mainDispatcher.dispatcher) {
        val vm = build()
        backgroundScope.launch { vm.podcast.collect {} }
        advanceUntilIdle()

        vm.toggleSubscribe()
        advanceUntilIdle()

        assertFalse(db.podcastDao().getPodcast(feedUrl)!!.subscribed)
    }
}
