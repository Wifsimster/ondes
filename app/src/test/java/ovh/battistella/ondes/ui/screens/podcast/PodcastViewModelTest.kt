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
import ovh.battistella.ondes.data.local.episodeId

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
        every { rss.fetch(feedUrl, any(), any()) } returns TestSupport.feedFetch(TestSupport.parsedFeed(
            title = "My Show",
            episodes = listOf(
                TestSupport.parsedEpisode(guid = "ep-1", title = "Kotlin Weekly", pubDate = 2),
                TestSupport.parsedEpisode(guid = "ep-2", title = "Scala Times", pubDate = 1),
            ),
        ))
        return PodcastViewModel(
            context = context,
            savedStateHandle = SavedStateHandle(mapOf("feedUrl" to feedUrl)),
            repository = repo,
            connection = connection,
            downloadManager = downloadManager,
            snackbar = snackbar,
            ioDispatcher = mainDispatcher.dispatcher,
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

        assertEquals(
            listOf(episodeId(feedUrl, "ep-1"), episodeId(feedUrl, "ep-2")),
            vm.episodes.value.map { it.id },
        )
        assertEquals("My Show", db.podcastDao().getPodcast(feedUrl)?.title)
    }

    /**
     * The list starts at one page and grows on demand, instead of reading a
     * whole back catalogue out of SQLite to fill the first screen (opt. 7).
     */
    @Test
    fun `episodes load a page at a time`() = runTest(mainDispatcher.dispatcher) {
        val vm = build()
        backgroundScope.launch { vm.episodes.collect {} }
        backgroundScope.launch { vm.episodeCount.collect {} }
        advanceUntilIdle()

        // A back catalogue larger than one page.
        db.episodeDao().insertNew(
            (1..60).map { i ->
                TestSupport.episode(
                    id = episodeId(feedUrl, "back-$i"),
                    feedUrl = feedUrl,
                    title = "Back $i",
                    pubDate = -i.toLong(),
                )
            }
        )
        advanceUntilIdle()

        assertEquals(62, vm.episodeCount.value)
        assertEquals(40, vm.episodes.value.size)

        vm.loadMore()
        advanceUntilIdle()
        assertEquals(62, vm.episodes.value.size)
    }

    @Test
    fun `the title search box filters episodes`() = runTest(mainDispatcher.dispatcher) {
        val vm = build()
        backgroundScope.launch { vm.filteredEpisodes.collect {} }
        advanceUntilIdle()

        vm.onQueryChange("kotlin")
        advanceUntilIdle()

        assertEquals(listOf(episodeId(feedUrl, "ep-1")), vm.filteredEpisodes.value.map { it.id })
    }

    @Test
    fun `unplayed-only hides finished episodes`() = runTest(mainDispatcher.dispatcher) {
        val vm = build()
        backgroundScope.launch { vm.filteredEpisodes.collect {} }
        advanceUntilIdle()
        db.episodeDao().setPlayed(episodeId(feedUrl, "ep-2"), true)
        advanceUntilIdle()

        vm.toggleUnplayedOnly()
        advanceUntilIdle()

        assertEquals(listOf(episodeId(feedUrl, "ep-1")), vm.filteredEpisodes.value.map { it.id })
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
