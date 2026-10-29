package ovh.battistella.ondes.ui.screens.podcast

import android.content.Context
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class PodcastViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle,
    private val repository: PodcastRepository,
    private val connection: PlaybackConnection,
    private val downloadManager: DownloadManager,
    private val snackbar: SnackbarController,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    val feedUrl: String = checkNotNull(savedStateHandle.get<String>("feedUrl"))

    val podcast: StateFlow<PodcastEntity?> = repository.observePodcast(feedUrl)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val pageLimit = MutableStateFlow(PAGE_SIZE)

    val episodes: StateFlow<List<EpisodeEntity>> = pageLimit
        .flatMapLatest { limit -> repository.observeEpisodesPaged(feedUrl, limit) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val episodeCount: StateFlow<Int> = repository.observeEpisodeCount(feedUrl)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _query = MutableStateFlow(savedStateHandle[KEY_QUERY] ?: "")
    val query = _query.asStateFlow()

    private val _unplayedOnly = MutableStateFlow(savedStateHandle[KEY_UNPLAYED_ONLY] ?: false)
    val unplayedOnly = _unplayedOnly.asStateFlow()

    val filteredEpisodes: StateFlow<List<EpisodeEntity>> =
        combine(
            _query.debounce { if (it.isBlank()) 0L else FILTER_DEBOUNCE_MS },
            _unplayedOnly,
            pageLimit,
        ) { q, unplayedOnly, limit -> Triple(q, unplayedOnly, limit) }
            .flatMapLatest { (q, unplayedOnly, limit) ->
                val source = if (q.isBlank()) {
                    repository.observeEpisodesPaged(feedUrl, limit)
                } else {
                    repository.observeEpisodes(feedUrl)
                }
                source.map { list ->
                    list
                        .let { items ->
                            if (q.isBlank()) items
                            else items.filter { it.title.contains(q.trim(), ignoreCase = true) }
                        }
                        .let { items -> if (unplayedOnly) items.filterNot { it.isFinished } else items }
                }
            }
            .flowOn(ioDispatcher)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val nowPlaying: StateFlow<NowPlaying> = connection.state
        .map { NowPlaying(it.currentEpisodeId, it.isPlaying) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NowPlaying())

    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()

    fun onQueryChange(value: String) {
        _query.value = value
        savedStateHandle[KEY_QUERY] = value
    }

    init { refresh() }

    fun toggleUnplayedOnly() {
        val next = !_unplayedOnly.value
        _unplayedOnly.value = next
        savedStateHandle[KEY_UNPLAYED_ONLY] = next
    }

    fun loadMore() {
        if (pageLimit.value >= episodeCount.value) return
        pageLimit.value += PAGE_SIZE
    }

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
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
                snackbar.showUndo(
                    text = context.getString(R.string.unsubscribed),
                    actionLabel = context.getString(R.string.undo),
                    action = { repository.subscribe(feedUrl) },
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

    fun setSpeed(speed: Float?) {
        viewModelScope.launch { repository.setPodcastSpeed(feedUrl, speed) }
    }

    fun setAutoDownload(enabled: Boolean) {
        viewModelScope.launch { repository.setPodcastAutoDownload(feedUrl, enabled) }
    }

    private companion object {
        const val PAGE_SIZE = 40

        const val FILTER_DEBOUNCE_MS = 200L

        const val KEY_QUERY = "podcast_query"
        const val KEY_UNPLAYED_ONLY = "podcast_unplayed_only"
    }
}
