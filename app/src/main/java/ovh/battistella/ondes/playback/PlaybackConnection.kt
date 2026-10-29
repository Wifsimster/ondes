package ovh.battistella.ondes.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import ovh.battistella.ondes.data.local.EpisodeEntity
import ovh.battistella.ondes.data.repository.PodcastRepository
import ovh.battistella.ondes.data.settings.OndesSettings
import ovh.battistella.ondes.data.settings.SettingsRepository
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class PlayerUiState(
    val isConnected: Boolean = false,
    val currentEpisodeId: String? = null,
    val title: String = "",
    val artworkUri: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
)

data class NowPlaying(
    val episodeId: String? = null,
    val isPlaying: Boolean = false,
)

@Singleton
class PlaybackConnection @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val repository: PodcastRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(PlayerUiState())
    val state = _state.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    @Volatile private var settings: OndesSettings = OndesSettings()

    private var tickerJob: Job? = null

    private fun connect() {
        if (controller != null || controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            .setListener(controllerListener)
            .buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                val c = future.get()
                controller = c
                c.addListener(playerListener)
                _state.value = _state.value.copy(isConnected = true)
                syncFromController()
            } catch (t: Throwable) {
                controller = null
                controllerFuture = null
                _state.value = _state.value.copy(isConnected = false)
            }
        }, MoreExecutors.directExecutor())
    }

    private fun requireController(): MediaController? {
        val c = controller
        if (c == null) connect()
        return c
    }

    private fun releaseController() {
        tickerJob?.cancel()
        tickerJob = null
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
        controllerFuture = null
        _state.value = _state.value.copy(isConnected = false, isPlaying = false)
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            releaseController()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFromController()
        }
    }

    init {
        scope.launch { settingsRepository.settings.collect { settings = it } }
        scope.launch {
            _state.subscriptionCount.collect { count ->
                if (count > 0) {
                    if (controller?.isPlaying == true) startPositionTicker()
                } else {
                    tickerJob?.cancel()
                    tickerJob = null
                }
            }
        }
        connect()
    }

    private fun startPositionTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                val c = controller ?: break
                if (!c.isPlaying) break
                if (_state.subscriptionCount.value == 0) break
                _state.value = _state.value.copy(
                    positionMs = c.currentPosition.coerceAtLeast(0),
                    durationMs = c.duration.let { if (it == C.TIME_UNSET) 0L else it },
                )
                delay(500)
            }
            tickerJob = null
        }
    }

    private fun syncFromController() {
        val c = controller ?: return
        if (c.isPlaying && _state.subscriptionCount.value > 0) startPositionTicker()
        else { tickerJob?.cancel(); tickerJob = null }
        val item = c.currentMediaItem
        _state.value = _state.value.copy(
            isConnected = true,
            currentEpisodeId = item?.mediaId?.takeIf { it.isNotBlank() },
            title = item?.mediaMetadata?.title?.toString() ?: "",
            artworkUri = item?.mediaMetadata?.artworkUri?.toString(),
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.let { if (it == C.TIME_UNSET) 0L else it },
            speed = c.playbackParameters.speed,
            hasNext = c.hasNextMediaItem(),
            hasPrevious = c.hasPreviousMediaItem(),
        )
    }

    fun play(episode: EpisodeEntity, queue: List<EpisodeEntity> = emptyList()) {
        val c = requireController() ?: return
        if (c.currentMediaItem?.mediaId == episode.id) {
            c.resume()
            return
        }
        val followOn = if (settings.autoAdvance && queue.isNotEmpty()) {
            PlaybackTransitions.followOn(episode, queue)
        } else {
            emptyList()
        }
        loadAndPlay(episode, followOn)
    }

    fun playFromQueue(queue: List<EpisodeEntity>, startIndex: Int) {
        val c = requireController() ?: return
        val start = queue.getOrNull(startIndex) ?: return
        if (c.currentMediaItem?.mediaId == start.id) {
            c.resume()
            return
        }
        loadAndPlay(start, queue.drop(startIndex + 1))
    }

    private fun loadAndPlay(head: EpisodeEntity, followOn: List<EpisodeEntity>) {
        scope.launch {
            val resolved = withContext(Dispatchers.IO) {
                MediaItems.playable(head)?.let { headItem ->
                    listOf(headItem) + followOn.mapNotNull(MediaItems::playable)
                }
            } ?: return@launch
            val c = controller ?: return@launch
            val startMs = PlaybackTransitions.resumeTargetMs(head.positionMs, head.isFinished) ?: 0L
            c.setMediaItems(resolved, 0, startMs)
            c.playbackParameters = PlaybackParameters(settings.defaultSpeed)
            applySpeedFor(head.feedUrl)
            c.prepare()
            c.play()
        }
    }

    private fun applySpeedFor(feedUrl: String) {
        scope.launch {
            val speed = repository.getPodcastOnce(feedUrl)?.overrideSpeed ?: settings.defaultSpeed
            controller?.playbackParameters = PlaybackParameters(speed)
        }
    }

    fun playPause() {
        val c = requireController() ?: return
        if (c.isPlaying) c.pause() else c.resume()
    }

    fun pause() { controller?.pause() }

    fun finishCurrentEpisode() {
        val id = controller?.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: return
        scope.launch {
            repository.setPlayed(id, true)
            repository.removeFromQueue(id)
        }
    }

    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs) }

    fun seekBack() {
        val c = requireController() ?: return
        c.seekTo((c.currentPosition - settings.skipBackMs).coerceAtLeast(0))
    }

    fun seekForward() {
        val c = requireController() ?: return
        val target = c.currentPosition + settings.skipForwardMs
        val duration = c.duration
        c.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
    }

    fun next() {
        val c = requireController() ?: return
        if (c.hasNextMediaItem()) c.seekToNextMediaItem()
    }

    fun previous() {
        val c = requireController() ?: return
        if (c.hasPreviousMediaItem()) c.seekToPreviousMediaItem() else c.seekTo(0)
    }

    fun setSpeed(speed: Float) {
        controller?.playbackParameters = PlaybackParameters(speed)
        val episodeId = _state.value.currentEpisodeId
        scope.launch {
            val feedUrl = episodeId?.let { repository.getEpisode(it)?.feedUrl }
            val hasOverride = feedUrl?.let { repository.getPodcastOnce(it)?.overrideSpeed } != null
            if (hasOverride && feedUrl != null) {
                repository.setPodcastSpeed(feedUrl, speed)
            } else {
                settingsRepository.setDefaultSpeed(speed)
            }
        }
    }

    fun stop() {
        controller?.run {
            pause()
            clearMediaItems()
        }
    }

    private fun MediaController.resume() {
        if (playbackState == Player.STATE_IDLE) prepare()
        play()
    }
}
