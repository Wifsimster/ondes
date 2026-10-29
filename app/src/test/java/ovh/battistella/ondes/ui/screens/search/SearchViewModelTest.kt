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
import androidx.lifecycle.SavedStateHandle

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
        val repo = TestSupport.repository(db, Dispatchers.Unconfined, rss = rss, search = search)
        return SearchViewModel(context, SavedStateHandle(), repo, SnackbarController())
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
        every { rss.fetch("https://example.com/feed.xml", any(), any()) } returns
            TestSupport.feedFetch(TestSupport.parsedFeed(title = "Pasted Show"))
        val vm = viewModel()
        val opened = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.openPodcast.collect { opened += it }
        }

        vm.onQueryChange("https://example.com/feed.xml")
        vm.search()
        advanceUntilIdle()

        assertEquals(listOf("https://example.com/feed.xml"), opened)
        assertTrue(vm.state.value.subscribedFeeds.contains("https://example.com/feed.xml"))
        assertEquals("Pasted Show", db.podcastDao().getPodcast("https://example.com/feed.xml")?.title)
    }

    @Test
    fun `already-subscribed shows are reflected in subscribedFeeds`() =
        runTest(mainDispatcher.dispatcher) {
            val vm = viewModel()
            db.podcastDao().upsert(TestSupport.podcast(feedUrl = "https://sub.example/feed"))
            advanceUntilIdle()

            assertTrue(vm.state.value.subscribedFeeds.contains("https://sub.example/feed"))
        }

    @Test
    fun `a failed subscribe from the browse landing does not replace the screen with an error`() =
        runTest(mainDispatcher.dispatcher) {
            every { rss.fetch(any(), any(), any()) } throws RuntimeException("offline")
            val vm = viewModel()

            vm.subscribe(TestSupport.searchResult(feedUrl = "https://x.example/feed"))
            advanceUntilIdle()

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
