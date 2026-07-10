package ovh.battistella.ondes.ui.screens.search

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import ovh.battistella.ondes.R
import ovh.battistella.ondes.common.SnackbarController
import ovh.battistella.ondes.data.local.OndesDatabase
import ovh.battistella.ondes.data.remote.PodcastSearchService
import ovh.battistella.ondes.data.remote.RssParser
import ovh.battistella.ondes.testing.TestSupport
import ovh.battistella.ondes.ui.theme.OndesTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Page-level test: renders the real [SearchScreen] against a real ViewModel +
 * in-memory repository, and drives it through the Compose UI-test APIs under
 * Robolectric (no emulator). Exercises UI → ViewModel → repository for real.
 */
@RunWith(RobolectricTestRunner::class)
class SearchScreenTest {

    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val search = mockk<PodcastSearchService>(relaxed = true)
    private val rss = mockk<RssParser>(relaxed = true)
    private lateinit var db: OndesDatabase

    private fun viewModel(): SearchViewModel {
        db = TestSupport.inMemoryDb()
        // Unconfined IO so repository work completes eagerly inside the test.
        val repo = TestSupport.repository(db, Dispatchers.Unconfined, rss = rss, search = search)
        return SearchViewModel(context, repo, SnackbarController())
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    private fun setScreen(vm: SearchViewModel, onOpen: (String) -> Unit = {}) {
        compose.setContent {
            OndesTheme(dynamicColor = false) {
                SearchScreen(contentPadding = PaddingValues(0.dp), onOpenPodcast = onOpen, viewModel = vm)
            }
        }
    }

    @Test
    fun rendersSearchField() {
        setScreen(viewModel())
        compose.onNodeWithText(context.getString(R.string.search_placeholder)).assertIsDisplayed()
    }

    @Test
    fun typingAndSearchingShowsResults() {
        every { search.search(any()) } returns listOf(
            TestSupport.searchResult(feedUrl = "https://a.example/feed", title = "Kotlin Talks"),
        )
        setScreen(viewModel())

        compose.onNode(hasSetTextAction()).performTextInput("kotlin")
        compose.onNode(hasSetTextAction()).performImeAction()

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Kotlin Talks").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Kotlin Talks").assertIsDisplayed()
    }
}
