package ovh.battistella.ondes.ui.screens.onboarding

import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import ovh.battistella.ondes.data.remote.PodcastSearchService
import ovh.battistella.ondes.data.remote.RssParser
import ovh.battistella.ondes.data.repository.PodcastRepository
import ovh.battistella.ondes.data.settings.SettingsRepository
import ovh.battistella.ondes.testing.MainDispatcherRule
import ovh.battistella.ondes.testing.TestSupport
import ovh.battistella.ondes.common.SnackbarController
import android.content.Context
import androidx.test.core.app.ApplicationProvider

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OnboardingViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val search = mockk<PodcastSearchService>(relaxed = true)
    private val rss = mockk<RssParser>(relaxed = true)
    private lateinit var db: OndesDatabase
    private lateinit var repo: PodcastRepository

    private fun build(): OnboardingViewModel {
        db = TestSupport.inMemoryDb()
        repo = TestSupport.repository(db, mainDispatcher.dispatcher, rss = rss, search = search)
        return OnboardingViewModel(context, repo, settingsRepository, SnackbarController())
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun `toggling a theme adds then removes it`() {
        val vm = build()
        val theme = vm.themes.first()
        vm.toggle(theme)
        assertEquals(setOf(theme.genreId), vm.state.value.selectedThemes)
        vm.toggle(theme)
        assertTrue(vm.state.value.selectedThemes.isEmpty())
    }

    @Test
    fun `proceeding to proposals dedupes shows that chart in two themes`() = runTest(mainDispatcher.dispatcher) {
        // Same feed returned for every theme -> a single deduped proposal.
        every { search.topPodcasts(any(), any()) } returns listOf(
            TestSupport.searchResult(feedUrl = "https://dup.example/feed", title = "Everywhere FM"),
        )
        val vm = build()
        vm.toggle(vm.themes[0])
        vm.toggle(vm.themes[1])

        vm.proceedToProposals()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(OnboardingStep.PROPOSALS, state.step)
        assertFalse(state.proposalsLoading)
        assertEquals(listOf("https://dup.example/feed"), state.proposals.map { it.feedUrl })
    }

    @Test
    fun `empty proposals are flagged as an error`() = runTest(mainDispatcher.dispatcher) {
        every { search.topPodcasts(any(), any()) } returns emptyList()
        val vm = build()
        vm.toggle(vm.themes[0])

        vm.proceedToProposals()
        advanceUntilIdle()

        assertTrue(vm.state.value.proposalsError)
    }

    @Test
    fun `finishing subscribes only the chosen shows and marks onboarding done`() = runTest(mainDispatcher.dispatcher) {
        every { search.topPodcasts(any(), any()) } returns listOf(
            TestSupport.searchResult(feedUrl = "https://pick.example/feed", title = "Chosen"),
            TestSupport.searchResult(feedUrl = "https://skip.example/feed", title = "Skipped"),
        )
        every { rss.fetch("https://pick.example/feed", any(), any()) } returns
            TestSupport.feedFetch(TestSupport.parsedFeed(title = "Chosen"))
        val vm = build()
        vm.toggle(vm.themes[0])
        vm.proceedToProposals()
        advanceUntilIdle()

        vm.toggleProposal(TestSupport.searchResult(feedUrl = "https://pick.example/feed"))
        vm.subscribeSelectedAndFinish()
        advanceUntilIdle()

        // Only the picked feed was subscribed; the skipped one never touched the DB.
        assertEquals("Chosen", db.podcastDao().getPodcast("https://pick.example/feed")?.title)
        assertEquals(null, db.podcastDao().getPodcast("https://skip.example/feed"))
        coVerify { settingsRepository.setOnboardingDone(true) }
    }

    @Test
    fun `skip just marks onboarding done`() = runTest(mainDispatcher.dispatcher) {
        val vm = build()
        vm.skip()
        advanceUntilIdle()
        coVerify { settingsRepository.setOnboardingDone(true) }
    }
}
