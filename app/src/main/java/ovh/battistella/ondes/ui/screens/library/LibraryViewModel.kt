package ovh.battistella.ondes.ui.screens.library

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ovh.battistella.ondes.R
import ovh.battistella.ondes.common.SnackbarController
import ovh.battistella.ondes.data.local.PodcastWithCount
import ovh.battistella.ondes.data.repository.PodcastRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** How the subscriptions grid is ordered. */
enum class LibrarySort { RECENT, ALPHABETICAL, UNPLAYED }

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val repository: PodcastRepository,
    private val snackbar: SnackbarController,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    // Restored from saved state: the chosen order used to reset to RECENT on
    // every rotation and every restore from background (issue P2).
    private val _sort = MutableStateFlow(
        savedStateHandle.get<String>(KEY_SORT)
            ?.let { name -> runCatching { LibrarySort.valueOf(name) }.getOrNull() }
            ?: LibrarySort.RECENT,
    )
    val sort: StateFlow<LibrarySort> = _sort.asStateFlow()

    /** feedUrls currently picked in multi-select mode (empty = not selecting). */
    private val _selectedFeedUrls = MutableStateFlow<Set<String>>(emptySet())
    val selectedFeedUrls: StateFlow<Set<String>> = _selectedFeedUrls.asStateFlow()

    val subscriptions: StateFlow<List<PodcastWithCount>> =
        combine(repository.observeSubscriptionsWithCounts(), _sort) { items, sort ->
            // Defensive dedupe so the feedUrl-keyed grid can never crash on a dup.
            val unique = items.distinctBy { it.podcast.feedUrl }
            when (sort) {
                LibrarySort.ALPHABETICAL ->
                    unique.sortedBy { it.podcast.title.lowercase() }
                LibrarySort.RECENT ->
                    unique.sortedByDescending { it.podcast.lastUpdated }
                LibrarySort.UNPLAYED ->
                    unique.sortedWith(
                        compareByDescending<PodcastWithCount> { it.unplayedCount }
                            .thenBy { it.podcast.title.lowercase() },
                    )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSort(sort: LibrarySort) {
        _sort.value = sort
        savedStateHandle[KEY_SORT] = sort.name
    }

    // --- multi-select ---

    /** Begin selection mode with [feedUrl] picked (typically from a long-press). */
    fun startSelection(feedUrl: String) {
        _selectedFeedUrls.value = setOf(feedUrl)
    }

    fun toggleSelection(feedUrl: String) {
        _selectedFeedUrls.value = _selectedFeedUrls.value.toMutableSet().apply {
            if (!add(feedUrl)) remove(feedUrl)
        }
    }

    fun clearSelection() {
        _selectedFeedUrls.value = emptySet()
    }

    /** Unsubscribe every picked feed, with a single undoable snackbar. */
    fun unsubscribeSelected() {
        val targets = _selectedFeedUrls.value
        if (targets.isEmpty()) return
        clearSelection()
        viewModelScope.launch {
            repository.unsubscribeAll(targets)
            snackbar.showUndo(
                text = context.resources.getQuantityString(
                    R.plurals.unsubscribed_count, targets.size, targets.size,
                ),
                actionLabel = context.getString(R.string.undo),
                // App-scoped so Undo survives leaving the Library screen (P1-19).
                action = { repository.resubscribeAll(targets) },
            )
        }
    }

    private companion object {
        const val KEY_SORT = "library_sort"
    }
}
