package ovh.battistella.ondes.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SleepTimer @Inject constructor(
    private val playbackConnection: PlaybackConnection,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private val _remainingMs = MutableStateFlow(0L)
    val remainingMs = _remainingMs.asStateFlow()

    private val _endOfEpisodeArmed = MutableStateFlow(false)
    val endOfEpisodeArmed = _endOfEpisodeArmed.asStateFlow()

    fun start(durationMs: Long) {
        cancel()
        _remainingMs.value = durationMs
        job = scope.launch {
            while (isActive && _remainingMs.value > 0) {
                delay(1_000)
                _remainingMs.value = (_remainingMs.value - 1_000).coerceAtLeast(0)
            }
            if (_remainingMs.value <= 0) {
                playbackConnection.pause()
            }
        }
    }

    fun startEndOfEpisode() {
        cancel()
        val targetId = playbackConnection.state.value.currentEpisodeId ?: return
        _endOfEpisodeArmed.value = true
        var lastPositionMs = 0L
        var lastDurationMs = 0L
        job = scope.launch {
            playbackConnection.state.collect { state ->
                val current = state.currentEpisodeId
                when {
                    current != targetId -> {
                        if (SleepTimerLogic.wasAutoAdvance(lastPositionMs, lastDurationMs)) {
                            playbackConnection.pause()
                        }
                        cancel()
                    }
                    SleepTimerLogic.isNearEnd(state.positionMs, state.durationMs, state.speed) -> {
                        playbackConnection.finishCurrentEpisode()
                        playbackConnection.pause()
                        cancel()
                    }
                    else -> {
                        lastPositionMs = state.positionMs
                        lastDurationMs = state.durationMs
                    }
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _remainingMs.value = 0
        _endOfEpisodeArmed.value = false
    }
}
