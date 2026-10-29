package ovh.battistella.ondes.ui.screens.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ovh.battistella.ondes.data.local.Chapter
import ovh.battistella.ondes.data.local.EpisodeEntity
import ovh.battistella.ondes.data.repository.PodcastRepository
import ovh.battistella.ondes.data.settings.SettingsRepository
import ovh.battistella.ondes.playback.PlaybackConnection
import ovh.battistella.ondes.playback.PlayerUiState
import ovh.battistella.ondes.playback.SleepTimer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val connection: PlaybackConnection,
    private val sleepTimer: SleepTimer,
    private val repository: PodcastRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val playerState: StateFlow<PlayerUiState> = connection.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlayerUiState())

    val queue: StateFlow<List<EpisodeEntity>> = repository.observeQueue()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val skipBackSeconds: StateFlow<Int> = settingsRepository.settings
        .map { (it.skipBackMs / 1000).toInt() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 10)
    val skipForwardSeconds: StateFlow<Int> = settingsRepository.settings
        .map { (it.skipForwardMs / 1000).toInt() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 30)

    val currentEpisode: StateFlow<EpisodeEntity?> = connection.state
        .map { it.currentEpisodeId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else repository.observeEpisode(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val chapters: StateFlow<List<Chapter>> = currentEpisode
        .distinctUntilChangedBy { it?.id }
        .flatMapLatest { episode ->
            if (episode == null || episode.chaptersUrl.isNullOrBlank()) flowOf(emptyList())
            else flow { emit(repository.chaptersFor(episode)) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sleepRemainingMs: StateFlow<Long> = sleepTimer.remainingMs
    val sleepEndOfEpisode: StateFlow<Boolean> = sleepTimer.endOfEpisodeArmed

    fun playPause() = connection.playPause()
    fun seekTo(ms: Long) = connection.seekTo(ms)
    fun seekBack() = connection.seekBack()
    fun seekForward() = connection.seekForward()
    fun next() = connection.next()
    fun previous() = connection.previous()
    fun setSpeed(speed: Float) = connection.setSpeed(speed)

    fun stop() = connection.stop()

    fun playQueueItem(index: Int) = connection.playFromQueue(queue.value, index)

    fun startSleepTimer(minutes: Int) = sleepTimer.start(minutes * 60_000L)
    fun startSleepAtEpisodeEnd() = sleepTimer.startEndOfEpisode()
    fun cancelSleepTimer() = sleepTimer.cancel()
}
