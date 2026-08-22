package ovh.battistella.ondes.ui.screens.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import ovh.battistella.ondes.common.SnackbarController
import ovh.battistella.ondes.data.local.OndesDatabase
import ovh.battistella.ondes.data.repository.PodcastRepository
import ovh.battistella.ondes.testing.MainDispatcherRule
import ovh.battistella.ondes.testing.TestSupport
import androidx.lifecycle.SavedStateHandle

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LibraryViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val snackbar = SnackbarController()
    private lateinit var db: OndesDatabase
    private lateinit var repo: PodcastRepository

    private fun build(): LibraryViewModel {
        db = TestSupport.inMemoryDb()
        repo = TestSupport.repository(db, mainDispatcher.dispatcher)
        return LibraryViewModel(SavedStateHandle(), repo, snackbar, context)
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun `subscriptions are sorted alphabetically when requested`() = runTest(mainDispatcher.dispatcher) {
        val vm = build()
        db.podcastDao().upsert(TestSupport.podcast(feedUrl = "f1", title = "Beta", lastUpdated = 2))
        db.podcastDao().upsert(TestSupport.podcast(feedUrl = "f2", title = "Alpha", lastUpdated = 1))
        backgroundScope.launch { vm.subscriptions.collect {} }

        vm.setSort(LibrarySort.ALPHABETICAL)
        advanceUntilIdle()

        assertEquals(listOf("Alpha", "Beta"), vm.subscriptions.value.map { it.podcast.title })
    }

    @Test
    fun `recent sort orders by last-updated descending`() = runTest(mainDispatcher.dispatcher) {
        val vm = build()
        db.podcastDao().upsert(TestSupport.podcast(feedUrl = "f1", title = "Older", lastUpdated = 1))
        db.podcastDao().upsert(TestSupport.podcast(feedUrl = "f2", title = "Newer", lastUpdated = 9))
        backgroundScope.launch { vm.subscriptions.collect {} }
        advanceUntilIdle()

        assertEquals(listOf("Newer", "Older"), vm.subscriptions.value.map { it.podcast.title })
    }

    @Test
    fun `selection toggles add and remove feeds`() {
        val vm = build()
        vm.startSelection("f1")
        assertEquals(setOf("f1"), vm.selectedFeedUrls.value)
        vm.toggleSelection("f2")
        assertEquals(setOf("f1", "f2"), vm.selectedFeedUrls.value)
        vm.toggleSelection("f1")
        assertEquals(setOf("f2"), vm.selectedFeedUrls.value)
        vm.clearSelection()
        assertTrue(vm.selectedFeedUrls.value.isEmpty())
    }

    @Test
    fun `unsubscribing selected soft-deletes feeds and offers undo`() = runTest(mainDispatcher.dispatcher) {
        val vm = build()
        db.podcastDao().upsert(TestSupport.podcast(feedUrl = "f1", title = "One"))
        db.podcastDao().upsert(TestSupport.podcast(feedUrl = "f2", title = "Two"))
        val messages = mutableListOf<SnackbarController.Message>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            snackbar.messages.collect { messages += it }
        }

        vm.startSelection("f1")
        vm.toggleSelection("f2")
        vm.unsubscribeSelected()
        advanceUntilIdle()

        assertFalse(db.podcastDao().getPodcast("f1")!!.subscribed)
        assertFalse(db.podcastDao().getPodcast("f2")!!.subscribed)
        assertTrue(vm.selectedFeedUrls.value.isEmpty())
        // An undoable snackbar was shown; invoking undo resubscribes them.
        assertEquals(1, messages.size)
        messages.first().onAction!!.invoke()
        advanceUntilIdle()
        assertTrue(db.podcastDao().getPodcast("f1")!!.subscribed)
        assertTrue(db.podcastDao().getPodcast("f2")!!.subscribed)
    }
}
