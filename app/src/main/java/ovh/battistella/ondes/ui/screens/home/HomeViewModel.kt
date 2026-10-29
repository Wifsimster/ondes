package ovh.battistella.ondes.ui.screens.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ovh.battistella.ondes.R
import ovh.battistella.ondes.common.SnackbarController
import ovh.battistella.ondes.data.local.DownloadState
import ovh.battistella.ondes.data.local.EpisodeEntity
import ovh.battistella.ondes.data.repository.PodcastRepository
import ovh.battistella.ondes.download.DownloadManager
import ovh.battistella.ondes.playback.NowPlaying
import ovh.battistella.ondes.playback.PlaybackConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val loading: Boolean = true,
    val inProgress: List<EpisodeEntity> = emptyList(),
    val latest: List<EpisodeEntity> = emptyList(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: PodcastRepository,
    private val connection: PlaybackConnection,
    private val downloadManager: DownloadManager,
    private val snackbar: SnackbarController,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        repository.observeInProgress(),
        repository.observeLatest(),
    ) { inProgress, latest ->
        HomeUiState(loading = false, inProgress = inProgress, latest = latest)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState(loading = true))

    val nowPlaying: StateFlow<NowPlaying> = connection.state
        .map { NowPlaying(it.currentEpisodeId, it.isPlaying) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NowPlaying())

    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            try {
                val feeds = repository.observeSubscriptions().first().map { it.feedUrl }
                repository.refreshAllSubscriptions(feeds)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                snackbar.show(context.getString(R.string.data_op_failed))
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun playToggle(episode: EpisodeEntity) {
        val state = connection.state.value
        if (state.currentEpisodeId == episode.id && state.isPlaying) connection.pause()
        else connection.play(episode, uiState.value.latest)
    }

    fun open(episode: EpisodeEntity) = connection.play(episode, uiState.value.latest)

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
}
