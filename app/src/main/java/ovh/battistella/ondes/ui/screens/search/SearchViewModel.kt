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

    private val _state = MutableStateFlow(
        SearchUiState(query = savedStateHandle[KEY_QUERY] ?: ""),
    )
    val state = _state.asStateFlow()

    private val _openPodcast = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openPodcast = _openPodcast.asSharedFlow()

    private var searchJob: Job? = null

    init {
        _state.value.themes.firstOrNull()?.let(::selectTheme)
        viewModelScope.launch {
            val feeds = repository.getSubscribedFeedUrlsOnce()
            _state.update { it.copy(subscribedFeeds = it.subscribedFeeds + feeds) }
        }
    }

    fun onQueryChange(query: String) {
        savedStateHandle[KEY_QUERY] = query
        searchJob?.cancel()
        if (query.isEmpty()) {
            _state.value = _state.value.copy(query = "", results = emptyList(), error = null, loading = false)
            return
        }
        _state.value = _state.value.copy(query = query, loading = false)
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

    fun selectTheme(theme: PodcastTheme) {
        if (_state.value.themeLoading && _state.value.selectedTheme == theme) return
        _state.value = _state.value.copy(
            selectedTheme = theme,
            themeLoading = true,
            themeResults = emptyList(),
        )
        viewModelScope.launch {
            val top = repository.topPodcasts(theme.genreId, limit = 15)
            if (_state.value.selectedTheme != theme) return@launch
            _state.value = _state.value.copy(themeLoading = false, themeResults = top)
        }
    }

    fun subscribe(result: PodcastSearchResult) {
        viewModelScope.launch {
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
