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

enum class LibrarySort { RECENT, ALPHABETICAL, UNPLAYED }

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val repository: PodcastRepository,
    private val snackbar: SnackbarController,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _sort = MutableStateFlow(
        savedStateHandle.get<String>(KEY_SORT)
            ?.let { name -> runCatching { LibrarySort.valueOf(name) }.getOrNull() }
            ?: LibrarySort.RECENT,
    )
    val sort: StateFlow<LibrarySort> = _sort.asStateFlow()

    private val _selectedFeedUrls = MutableStateFlow<Set<String>>(emptySet())
    val selectedFeedUrls: StateFlow<Set<String>> = _selectedFeedUrls.asStateFlow()

    val subscriptions: StateFlow<List<PodcastWithCount>> =
        combine(repository.observeSubscriptionsWithCounts(), _sort) { items, sort ->
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
                action = { repository.resubscribeAll(targets) },
            )
        }
    }

    private companion object {
        const val KEY_SORT = "library_sort"
    }
}
