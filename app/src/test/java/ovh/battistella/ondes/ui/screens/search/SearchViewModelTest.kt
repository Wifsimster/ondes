package ovh.battistella.ondes.ui.screens.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ovh.battistella.ondes.common.SnackbarController
import ovh.battistella.ondes.data.local.OndesDatabase
import ovh.battistella.ondes.data.remote.PodcastSearchService
import ovh.battistella.ondes.data.remote.RssParser
import ovh.battistella.ondes.testing.MainDispatcherRule
import ovh.battistella.ondes.testing.TestSupport

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SearchViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val search = mockk<PodcastSearchService>(relaxed = true)
    private val rss = mockk<RssParser>(relaxed = true)
    private lateinit var db: OndesDatabase

    private fun viewModel(): SearchViewModel {
        db = TestSupport.inMemoryDb()
        // Unconfined IO so repository work — including the refresh's Room
        // withTransaction — completes eagerly inline rather than depending on the
        // StandardTestDispatcher's virtual clock (which room-ktx's transaction
        // dispatcher doesn't advance under a detached launch). Mirrors SearchScreenTest.
        val repo = TestSupport.repository(db, Dispatchers.Unconfined, rss = rss, search = search)
        return SearchViewModel(context, repo, SnackbarController())
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun `submitting a text query loads results`() = runTest(mainDispatcher.dispatcher) {
        every { search.search("kotlin") } returns listOf(
            TestSupport.searchResult(feedUrl = "https://a.example/feed", title = "Kotlin Talks"),
        )
        val vm = viewModel()

        vm.onQueryChange("kotlin")
        vm.search()
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.loading)
        assertNull(state.error)
        assertEquals(listOf("https://a.example/feed"), state.results.map { it.feedUrl })
    }

    @Test
    fun `a failed search surfaces an error and clears results`() = runTest(mainDispatcher.dispatcher) {
        every { search.search(any()) } throws RuntimeException("offline")
        val vm = viewModel()

        vm.onQueryChange("kotlin")
        vm.search()
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.loading)
        assertTrue(state.results.isEmpty())
        assertEquals(
            context.getString(ovh.battistella.ondes.R.string.search_failed),
            state.error,
        )
    }

    @Test
    fun `clearing the query returns to the browse landing`() = runTest(mainDispatcher.dispatcher) {
        every { search.search(any()) } returns listOf(TestSupport.searchResult())
        val vm = viewModel()
        vm.onQueryChange("kotlin")
        vm.search()
        advanceUntilIdle()

        vm.onQueryChange("")
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals("", state.query)
        assertTrue(state.results.isEmpty())
        assertNull(state.error)
    }

    @Test
    fun `pasting a feed URL subscribes and emits the show to open`() = runTest(mainDispatcher.dispatcher) {
        every { rss.fetchAndParse("https://example.com/feed.xml") } returns
            TestSupport.parsedFeed(title = "Pasted Show")
        val vm = viewModel()
        // openPodcast is a replay-0 SharedFlow, so subscribe (eagerly, via an
        // unconfined dispatcher) before triggering the emission.
        val opened = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.openPodcast.collect { opened += it }
        }

        vm.onQueryChange("https://example.com/feed.xml")
        vm.search()
        advanceUntilIdle()

        // The newly-subscribed show is emitted for navigation, and the row is flagged.
        assertEquals(listOf("https://example.com/feed.xml"), opened)
        assertTrue(vm.state.value.subscribedFeeds.contains("https://example.com/feed.xml"))
        // And it really landed in the database.
        assertEquals("Pasted Show", db.podcastDao().getPodcast("https://example.com/feed.xml")?.title)
    }

    @Test
    fun `already-subscribed shows are reflected in subscribedFeeds`() =
        runTest(mainDispatcher.dispatcher) {
            val vm = viewModel()
            db.podcastDao().upsert(TestSupport.podcast(feedUrl = "https://sub.example/feed"))
            advanceUntilIdle()

            // Derived from the repository, so a row already subscribed elsewhere
            // renders as "Subscribed" rather than offering Subscribe again (P1-17).
            assertTrue(vm.state.value.subscribedFeeds.contains("https://sub.example/feed"))
        }

    @Test
    fun `a failed subscribe from the browse landing does not replace the screen with an error`() =
        runTest(mainDispatcher.dispatcher) {
            every { rss.fetchAndParse(any()) } throws RuntimeException("offline")
            val vm = viewModel()

            vm.subscribe(TestSupport.searchResult(feedUrl = "https://x.example/feed"))
            advanceUntilIdle()

            // The failure is a snackbar, not the full-screen error state that would
            // trap the user on the blank-query Browse landing (P1-18).
            assertNull(vm.state.value.error)
        }

    @Test
    fun `selecting a theme loads its proposed shows`() = runTest(mainDispatcher.dispatcher) {
        every { search.topPodcasts(any(), any()) } returns listOf(
            TestSupport.searchResult(feedUrl = "https://t.example/feed", title = "Top Show"),
        )
        val vm = viewModel()
        val theme = vm.state.value.themes.first()

        vm.selectTheme(theme)
        advanceUntilIdle()

        assertEquals(theme, vm.state.value.selectedTheme)
        assertFalse(vm.state.value.themeLoading)
        assertEquals(listOf("https://t.example/feed"), vm.state.value.themeResults.map { it.feedUrl })
    }
}
