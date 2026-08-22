package ovh.battistella.ondes.ui.screens.search

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ovh.battistella.ondes.R
import ovh.battistella.ondes.common.SnackbarController
import ovh.battistella.ondes.data.remote.PodcastSearchResult
import ovh.battistella.ondes.data.remote.PodcastTheme
import ovh.battistella.ondes.data.remote.PodcastThemes
import ovh.battistella.ondes.data.repository.PodcastRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<PodcastSearchResult> = emptyList(),
    val subscribedFeeds: Set<String> = emptySet(),
    val error: String? = null,
    // Browse-by-theme: a proposition of top shows for the picked theme.
    val themes: List<PodcastTheme> = PodcastThemes.all,
    val selectedTheme: PodcastTheme? = null,
    val themeLoading: Boolean = false,
    val themeResults: List<PodcastSearchResult> = emptyList(),
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle,
    private val repository: PodcastRepository,
    private val snackbar: SnackbarController,
) : ViewModel() {

    // Seeded from saved state so a rotation (or the process being killed in the
    // background) doesn't silently empty the search box (issue P2).
    private val _state = MutableStateFlow(
        SearchUiState(query = savedStateHandle[KEY_QUERY] ?: ""),
    )
    val state = _state.asStateFlow()

    /** One-shot: a feed URL to open (e.g. after a successful paste-a-URL subscribe). */
    private val _openPodcast = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openPodcast = _openPodcast.asSharedFlow()

    private var searchJob: Job? = null

    init {
        // Open Discover already proposing the top shows from the first theme.
        _state.value.themes.firstOrNull()?.let(::selectTheme)
        // Reflect the shows already subscribed when Search opens, so they aren't
        // offered "Subscribe" again (issue P1-17). Read once rather than collecting
        // the subscriptions Flow live: a live table observer re-enters a feed
        // refresh's Room transaction (subscribing inserts inside one), which the
        // test harness's inline executor surfaces as an IllegalStateException.
        // Subscribes made from this screen keep the set current below.
        viewModelScope.launch {
            val feeds = repository.getSubscribedFeedUrlsOnce()
            _state.update { it.copy(subscribedFeeds = it.subscribedFeeds + feeds) }
        }
    }

    fun onQueryChange(query: String) {
        savedStateHandle[KEY_QUERY] = query
        // Any pending debounce belongs to an older query — including when the
        // input has just become a URL, where the search that was already in
        // flight used to land afterwards and replace the screen with stale
        // results (issue P2).
        searchJob?.cancel()
        // Clearing the field returns to the Browse-by-theme landing rather than
        // leaving stale results (or an error) on screen.
        if (query.isEmpty()) {
            _state.value = _state.value.copy(query = "", results = emptyList(), error = null)
            return
        }
        _state.value = _state.value.copy(query = query)
        // Debounced as-you-type search; a raw URL waits for an explicit submit.
        if (!looksLikeUrl(query)) {
            searchJob = viewModelScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                runSearch(query.trim())
            }
        }
    }

    fun search() {
        searchJob?.cancel()
        val q = _state.value.query.trim()
        if (q.isEmpty()) return
        // Allow pasting a raw feed URL directly.
        if (looksLikeUrl(q)) {
            viewModelScope.launch {
                _state.value = _state.value.copy(loading = true, error = null)
                subscribeByUrl(q)
            }
            return
        }
        searchJob = viewModelScope.launch { runSearch(q) }
    }

    private suspend fun runSearch(q: String) {
        if (q.isEmpty()) return
        _state.value = _state.value.copy(loading = true, error = null)
        repository.search(q).fold(
            onSuccess = { results ->
                // An empty list here is a successful "no matches" (the screen shows
                // its own no-matches state), not a retryable error.
                _state.value = _state.value.copy(loading = false, results = results, error = null)
            },
            onFailure = {
                _state.value = _state.value.copy(
                    loading = false,
                    results = emptyList(),
                    error = context.getString(R.string.search_failed),
                )
            },
        )
    }

    private fun looksLikeUrl(s: String): Boolean = s.trim().startsWith("http")

    /** Pick a theme and load its top shows as a proposition. */
    fun selectTheme(theme: PodcastTheme) {
        if (_state.value.themeLoading && _state.value.selectedTheme == theme) return
        _state.value = _state.value.copy(
            selectedTheme = theme,
            themeLoading = true,
            themeResults = emptyList(),
        )
        viewModelScope.launch {
            val top = repository.topPodcasts(theme.genreId, limit = 15)
            // Ignore a stale response if the user has since tapped another chip.
            if (_state.value.selectedTheme != theme) return@launch
            _state.value = _state.value.copy(themeLoading = false, themeResults = top)
        }
    }

    fun subscribe(result: PodcastSearchResult) {
        viewModelScope.launch {
            // A subscribe failure is surfaced as a transient snackbar, not the
            // full-screen `error` state: on the blank-query Browse landing that
            // error replaced the whole UI with a "Try again" that calls search()
            // and early-returns on the empty query — trapping the user (P1-18).
            if (repository.subscribe(result.feedUrl).isSuccess) {
                _state.update { it.copy(subscribedFeeds = it.subscribedFeeds + result.feedUrl) }
            } else {
                snackbar.show(context.getString(R.string.search_feed_error))
            }
        }
    }

    private suspend fun subscribeByUrl(url: String) {
        val outcome = repository.subscribe(url)
        if (outcome.isSuccess) {
            // Success is a happy path, not an error: clear the field and open the
            // newly-subscribed show rather than showing a failure-styled message.
            _state.value = _state.value.copy(
                loading = false,
                query = "",
                results = emptyList(),
                error = null,
                subscribedFeeds = _state.value.subscribedFeeds + url,
            )
            _openPodcast.tryEmit(url)
        } else {
            _state.value = _state.value.copy(
                loading = false,
                error = context.getString(R.string.search_feed_error),
            )
        }
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 350L

        private const val KEY_QUERY = "search_query"
    }
}
