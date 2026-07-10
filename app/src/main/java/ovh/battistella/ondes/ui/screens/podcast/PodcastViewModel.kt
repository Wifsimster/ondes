package ovh.battistella.ondes.ui.screens.podcast

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ovh.battistella.ondes.R
import ovh.battistella.ondes.common.SnackbarController
import ovh.battistella.ondes.data.local.EpisodeEntity
import ovh.battistella.ondes.data.local.PodcastEntity
import ovh.battistella.ondes.data.repository.PodcastRepository
import ovh.battistella.ondes.download.DownloadManager
import ovh.battistella.ondes.playback.NowPlaying
import ovh.battistella.ondes.playback.PlaybackConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PodcastViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val repository: PodcastRepository,
    private val connection: PlaybackConnection,
    private val downloadManager: DownloadManager,
    private val snackbar: SnackbarController,
) : ViewModel() {

    val feedUrl: String = Uri.decode(checkNotNull(savedStateHandle.get<String>("feedUrl")))

    val podcast: StateFlow<PodcastEntity?> = repository.observePodcast(feedUrl)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val episodes: StateFlow<List<EpisodeEntity>> = repository.observeEpisodes(feedUrl)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    private val _unplayedOnly = MutableStateFlow(false)
    val unplayedOnly = _unplayedOnly.asStateFlow()

    /** Episodes filtered by the title search box (#10) and the unplayed-only toggle. */
    val filteredEpisodes: StateFlow<List<EpisodeEntity>> =
        combine(episodes, _query, _unplayedOnly) { list, q, unplayedOnly ->
            list
                .let { items ->
                    if (q.isBlank()) items
                    else items.filter { it.title.contains(q.trim(), ignoreCase = true) }
                }
                .let { items -> if (unplayedOnly) items.filterNot { it.isFinished } else items }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Only the current-episode / playing signal drives list rows; the 2 Hz
    // position ticks are dropped so the list doesn't recompose during playback.
    val nowPlaying: StateFlow<NowPlaying> = connection.state
        .map { NowPlaying(it.currentEpisodeId, it.isPlaying) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NowPlaying())

    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()

    fun onQueryChange(value: String) { _query.value = value }

    init { refresh() }

    fun toggleUnplayedOnly() {
        _unplayedOnly.value = !_unplayedOnly.value
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _refreshing.value = true
            // Tell the user when a manual pull-to-refresh fails instead of just
            // stopping the spinner with no feedback.
            runCatching { repository.refreshFeed(feedUrl) }
                .onFailure { snackbar.show(context.getString(R.string.data_op_failed)) }
            _refreshing.value = false
        }
    }

    fun toggleSubscribe() {
        viewModelScope.launch {
            val current = podcast.value
            if (current?.subscribed == true) {
                repository.unsubscribe(feedUrl)
                snackbar.show(
                    text = context.getString(R.string.unsubscribed),
                    actionLabel = context.getString(R.string.undo),
                    onAction = { viewModelScope.launch { repository.subscribe(feedUrl) } },
                )
            } else {
                if (repository.subscribe(feedUrl).isFailure) {
                    snackbar.show(context.getString(R.string.search_feed_error))
                }
            }
        }
    }

    fun playToggle(episode: EpisodeEntity) {
        val state = connection.state.value
        if (state.currentEpisodeId == episode.id && state.isPlaying) connection.pause()
        else connection.play(episode, episodes.value)
    }

    /** Load (and resume) an episode so the now-playing screen has something to show. */
    fun open(episode: EpisodeEntity) = connection.play(episode, episodes.value)

    fun download(episode: EpisodeEntity) = downloadManager.enqueue(episode.id)
    fun deleteDownload(episode: EpisodeEntity) =
        downloadManager.deleteDownload(episode.id, episode.localFilePath)

    fun markPlayed(episode: EpisodeEntity, played: Boolean) {
        viewModelScope.launch { repository.setPlayed(episode.id, played) }
    }

    fun playNext(episode: EpisodeEntity) {
        viewModelScope.launch { repository.playNextInQueue(episode.id) }
    }

    fun addToQueue(episode: EpisodeEntity) {
        viewModelScope.launch { repository.addToQueueEnd(episode.id) }
    }

    /** Per-podcast playback speed; null restores the global default (#7). */
    fun setSpeed(speed: Float?) {
        viewModelScope.launch { repository.setPodcastSpeed(feedUrl, speed) }
    }

    fun setAutoDownload(enabled: Boolean) {
        viewModelScope.launch { repository.setPodcastAutoDownload(feedUrl, enabled) }
    }
}
