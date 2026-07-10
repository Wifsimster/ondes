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

/** Counts down and pauses playback when it reaches zero. */
@Singleton
class SleepTimer @Inject constructor(
    private val playbackConnection: PlaybackConnection,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private val _remainingMs = MutableStateFlow(0L)
    /** Milliseconds left, or 0 when inactive. */
    val remainingMs = _remainingMs.asStateFlow()

    private val _endOfEpisodeArmed = MutableStateFlow(false)
    /** True when the timer is set to stop at the end of the current episode. */
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

    /** Pause playback when the episode that is current right now finishes. */
    fun startEndOfEpisode() {
        cancel()
        val targetId = playbackConnection.state.value.currentEpisodeId ?: return
        _endOfEpisodeArmed.value = true
        job = scope.launch {
            playbackConnection.state.collect { state ->
                val current = state.currentEpisodeId
                when {
                    // Already auto-advanced past the target: the service marked it
                    // finished on the transition, so just stop the next episode.
                    current != targetId -> {
                        playbackConnection.pause()
                        cancel()
                    }
                    // About to hit the end. Stopping here is a beat early, so mark
                    // the episode finished ourselves — the natural end-of-media
                    // event never fires once we pause. The threshold scales with
                    // speed so a fast playhead can't step over the window.
                    SleepTimerLogic.isNearEnd(state.positionMs, state.durationMs, state.speed) -> {
                        playbackConnection.finishCurrentEpisode()
                        playbackConnection.pause()
                        cancel()
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
