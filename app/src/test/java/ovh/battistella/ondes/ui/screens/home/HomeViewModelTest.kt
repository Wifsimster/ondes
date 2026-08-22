package ovh.battistella.ondes.ui.screens.home

import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ovh.battistella.ondes.data.local.OndesDatabase
import ovh.battistella.ondes.data.repository.PodcastRepository
import ovh.battistella.ondes.download.DownloadManager
import ovh.battistella.ondes.playback.PlaybackConnection
import ovh.battistella.ondes.playback.PlayerUiState
import ovh.battistella.ondes.testing.MainDispatcherRule
import ovh.battistella.ondes.testing.TestSupport
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ovh.battistella.ondes.common.SnackbarController

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val snackbar = SnackbarController()
    private val playerFlow = MutableStateFlow(PlayerUiState())
    private val downloadManager = mockk<DownloadManager>(relaxed = true)
    private lateinit var db: OndesDatabase
    private lateinit var repo: PodcastRepository
    private lateinit var connection: PlaybackConnection

    private fun build(): HomeViewModel {
        db = TestSupport.inMemoryDb()
        repo = TestSupport.repository(db, mainDispatcher.dispatcher)
        connection = TestSupport.mockConnection(playerFlow)
        return HomeViewModel(context, repo, connection, downloadManager, snackbar)
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun `home flips from loading to loaded with latest and in-progress`() = runTest(mainDispatcher.dispatcher) {
        val vm = build()
        assertTrue(vm.uiState.value.loading)
        db.podcastDao().upsert(TestSupport.podcast(feedUrl = "f1", subscribed = true))
        db.episodeDao().upsertAll(
            listOf(
                TestSupport.episode(id = "fresh", feedUrl = "f1", pubDate = 50),
                TestSupport.episode(id = "started", feedUrl = "f1", pubDate = 40, positionMs = 5_000),
            ),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val ui = vm.uiState.value
        assertFalse(ui.loading)
        assertTrue(ui.latest.any { it.id == "fresh" })
        assertEquals(listOf("started"), ui.inProgress.map { it.id })
    }

    @Test
    fun `markPlayed persists the played flag`() = runTest(mainDispatcher.dispatcher) {
        val vm = build()
        db.episodeDao().upsertAll(listOf(TestSupport.episode(id = "ep-1")))

        vm.markPlayed(TestSupport.episode(id = "ep-1"), played = true)
        advanceUntilIdle()

        assertTrue(db.episodeDao().getEpisode("ep-1")!!.isPlayed)
    }

    @Test
    fun `addToQueue enqueues the episode`() = runTest(mainDispatcher.dispatcher) {
        val vm = build()
        db.episodeDao().upsertAll(listOf(TestSupport.episode(id = "ep-1")))

        vm.addToQueue(TestSupport.episode(id = "ep-1"))
        advanceUntilIdle()

        assertEquals(listOf("ep-1"), db.queueDao().getOrderedIds())
    }

    @Test
    fun `playToggle plays a fresh episode`() = runTest(mainDispatcher.dispatcher) {
        val vm = build()
        val ep = TestSupport.episode(id = "ep-1")

        vm.playToggle(ep)

        verify { connection.play(ep, any()) }
    }

    @Test
    fun `download delegates to the download manager`() {
        val vm = build()
        vm.download(TestSupport.episode(id = "ep-1"))
        verify { downloadManager.enqueue("ep-1") }
    }
}
