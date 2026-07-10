package ovh.battistella.ondes.ui.screens.queue

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ovh.battistella.ondes.common.SnackbarController
import ovh.battistella.ondes.data.local.OndesDatabase
import ovh.battistella.ondes.data.local.QueueItemEntity
import ovh.battistella.ondes.data.repository.PodcastRepository
import ovh.battistella.ondes.playback.NowPlaying
import ovh.battistella.ondes.playback.PlaybackConnection
import ovh.battistella.ondes.playback.PlayerUiState
import ovh.battistella.ondes.testing.MainDispatcherRule
import ovh.battistella.ondes.testing.TestSupport

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class QueueViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val snackbar = SnackbarController()
    private val playerFlow = MutableStateFlow(PlayerUiState())
    private lateinit var db: OndesDatabase
    private lateinit var repo: PodcastRepository
    private lateinit var connection: PlaybackConnection

    private fun build(): QueueViewModel {
        db = TestSupport.inMemoryDb()
        repo = TestSupport.repository(db, mainDispatcher.dispatcher)
        connection = TestSupport.mockConnection(playerFlow)
        return QueueViewModel(context, repo, connection, snackbar)
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    /** Seed three queued episodes ep-1..ep-3 in order. */
    private suspend fun seedQueue() {
        listOf("ep-1", "ep-2", "ep-3").forEachIndexed { i, id ->
            db.episodeDao().upsertAll(listOf(TestSupport.episode(id = id, title = "E$i")))
            db.queueDao().upsert(QueueItemEntity(id, i.toLong()))
        }
    }

    @Test
    fun `queue is exposed in sort order`() = runTest(mainDispatcher.dispatcher) {
        val vm = build()
        seedQueue()
        backgroundScope.launch { vm.queue.collect {} }
        advanceUntilIdle()

        assertEquals(listOf("ep-1", "ep-2", "ep-3"), vm.queue.value.map { it.id })
    }

    @Test
    fun `moveDown reorders the queue`() = runTest(mainDispatcher.dispatcher) {
        val vm = build()
        seedQueue()
        backgroundScope.launch { vm.queue.collect {} }
        advanceUntilIdle()

        vm.moveDown(0)
        advanceUntilIdle()

        assertEquals(listOf("ep-2", "ep-1", "ep-3"), db.queueDao().getOrderedIds())
    }

    @Test
    fun `removing an episode drops it and offers undo`() = runTest(mainDispatcher.dispatcher) {
        val vm = build()
        seedQueue()
        backgroundScope.launch { vm.queue.collect {} }
        val messages = mutableListOf<SnackbarController.Message>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            snackbar.messages.collect { messages += it }
        }
        advanceUntilIdle()

        vm.remove(TestSupport.episode(id = "ep-2"))
        advanceUntilIdle()

        assertEquals(listOf("ep-1", "ep-3"), db.queueDao().getOrderedIds())
        assertEquals(1, messages.size)
        // Undo restores the previous order (ep-2 back in the middle).
        messages.first().onAction!!.invoke()
        advanceUntilIdle()
        assertEquals(listOf("ep-1", "ep-2", "ep-3"), db.queueDao().getOrderedIds())
    }

    @Test
    fun `nowPlaying ignores position-only ticks`() = runTest(mainDispatcher.dispatcher) {
        val vm = build()
        val seen = mutableListOf<NowPlaying>()
        backgroundScope.launch { vm.nowPlaying.collect { seen += it } }
        advanceUntilIdle()

        playerFlow.value = PlayerUiState(currentEpisodeId = "ep-1", isPlaying = true, positionMs = 1_000)
        advanceUntilIdle()
        // Same episode + play state, only the position moved: the 2 Hz ticker.
        playerFlow.value = PlayerUiState(currentEpisodeId = "ep-1", isPlaying = true, positionMs = 9_000)
        advanceUntilIdle()

        // The position-only change must not produce a second now-playing emission.
        assertEquals(listOf(NowPlaying(), NowPlaying("ep-1", isPlaying = true)), seen)
    }

    @Test
    fun `clear empties the queue`() = runTest(mainDispatcher.dispatcher) {
        val vm = build()
        seedQueue()
        vm.clear()
        advanceUntilIdle()
        assertTrue(db.queueDao().getOrderedIds().isEmpty())
    }

    @Test
    fun `playToggle pauses when the tapped episode is already playing`() = runTest(mainDispatcher.dispatcher) {
        val vm = build()
        seedQueue()
        backgroundScope.launch { vm.queue.collect {} }
        advanceUntilIdle()
        playerFlow.value = PlayerUiState(currentEpisodeId = "ep-1", isPlaying = true)

        vm.playToggle(TestSupport.episode(id = "ep-1"))

        verify { connection.pause() }
    }

    @Test
    fun `playToggle starts the queue at the tapped episode`() = runTest(mainDispatcher.dispatcher) {
        val vm = build()
        seedQueue()
        backgroundScope.launch { vm.queue.collect {} }
        advanceUntilIdle()

        vm.playToggle(TestSupport.episode(id = "ep-2"))

        verify { connection.playFromQueue(any(), 1) }
    }
}
