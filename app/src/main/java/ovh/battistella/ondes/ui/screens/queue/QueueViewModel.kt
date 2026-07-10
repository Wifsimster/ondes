package ovh.battistella.ondes.ui.screens.queue

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ovh.battistella.ondes.R
import ovh.battistella.ondes.common.SnackbarController
import ovh.battistella.ondes.data.local.EpisodeEntity
import ovh.battistella.ondes.data.repository.PodcastRepository
import ovh.battistella.ondes.playback.NowPlaying
import ovh.battistella.ondes.playback.PlaybackConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class QueueViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: PodcastRepository,
    private val connection: PlaybackConnection,
    private val snackbar: SnackbarController,
) : ViewModel() {

    val queue: StateFlow<List<EpisodeEntity>> = repository.observeQueue()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Minimal now-playing signal for the rows; position ticks are dropped so the
    // queue doesn't recompose on every tick during playback (opt. 3).
    val nowPlaying: StateFlow<NowPlaying> = connection.state
        .map { NowPlaying(it.currentEpisodeId, it.isPlaying) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NowPlaying())

    /** Tapping a row plays the queue starting there; tapping the current one toggles. */
    fun playToggle(episode: EpisodeEntity) {
        val state = connection.state.value
        if (state.currentEpisodeId == episode.id && state.isPlaying) {
            connection.pause()
            return
        }
        val index = queue.value.indexOfFirst { it.id == episode.id }
        if (index >= 0) connection.playFromQueue(queue.value, index)
    }

    fun open(episode: EpisodeEntity) {
        val index = queue.value.indexOfFirst { it.id == episode.id }
        if (index >= 0) connection.playFromQueue(queue.value, index)
    }

    fun remove(episode: EpisodeEntity) {
        // Snapshot the order first so "Undo" can re-insert at the same position.
        val previousOrder = queue.value.map { it.id }
        viewModelScope.launch {
            repository.removeFromQueue(episode.id)
            snackbar.showUndo(
                text = context.getString(R.string.removed_from_queue),
                actionLabel = context.getString(R.string.undo),
                // App-scoped so Undo works even after the Queue screen is gone (P1-19).
                action = { repository.setQueueOrder(previousOrder) },
            )
        }
    }

    fun clear() {
        viewModelScope.launch { repository.clearQueue() }
    }

    fun moveUp(index: Int) = reorder(index, index - 1)
    fun moveDown(index: Int) = reorder(index, index + 1)

    private fun reorder(from: Int, to: Int) {
        val ids = queue.value.map { it.id }.toMutableList()
        if (from !in ids.indices || to !in ids.indices) return
        val moved = ids.removeAt(from)
        ids.add(to, moved)
        viewModelScope.launch { repository.setQueueOrder(ids) }
    }
}
