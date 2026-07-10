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

/**
 * App-wide bridge to [PlaybackService]. Connects a [MediaController] and
 * exposes a single [PlayerUiState] flow that any screen (mini-player, now
 * playing) can observe.
 */
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

    /** Latest settings snapshot, kept current so playback honours live changes. */
    @Volatile private var settings: OndesSettings = OndesSettings()

    /** Smooth-progress ticker; only alive while something is actually playing. */
    private var tickerJob: Job? = null

    private fun connect() {
        // Idempotent: never open a second controller while one is live or a
        // connection is already in flight.
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
                // Session connection failed — reset so the next command retries
                // instead of the app crashing on an unguarded future.get()
                // (issue P1-1).
                controller = null
                controllerFuture = null
                _state.value = _state.value.copy(isConnected = false)
            }
        }, MoreExecutors.directExecutor())
    }

    /**
     * Return the live controller, or trigger a (lazy) reconnect and return null.
     * The service can be torn down while the app process survives — without this
     * the singleton kept a dead controller forever and every transport button
     * silently no-oped (issue P0-4).
     */
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
            // The service died: drop the stale controller so the next command
            // reconnects to a fresh session.
            releaseController()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFromController()
        }
    }

    init {
        // Declared after the listeners so both are initialised before connect()
        // wires them into the controller during construction.
        scope.launch { settingsRepository.settings.collect { settings = it } }
        connect()
    }

    private fun startPositionTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                val c = controller ?: break
                if (!c.isPlaying) break
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
        // Run the smooth-progress ticker only while playing; stop it otherwise so
        // the main thread isn't woken every 500ms for the process's whole life.
        if (c.isPlaying) startPositionTicker() else { tickerJob?.cancel(); tickerJob = null }
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

    /**
     * Play [episode], resuming from its saved position. When auto-advance is on
     * and a [queue] is supplied, the episodes after it are loaded too so
     * playback flows continuously into the next one.
     */
    fun play(episode: EpisodeEntity, queue: List<EpisodeEntity> = emptyList()) {
        val c = requireController() ?: return
        // If this episode is already loaded, just resume.
        if (c.currentMediaItem?.mediaId == episode.id) {
            c.resume()
            return
        }

        // The requested episode must itself be playable and sits at index 0, so
        // the start position below always refers to it. If it has no playable
        // source (no download, no safe http URL) there's nothing to do — don't
        // hand the player an empty/broken item.
        val head = MediaItems.playable(episode) ?: return
        val followOn = if (settings.autoAdvance && queue.isNotEmpty()) {
            // Episodes strictly after the requested one, so playback flows on.
            queue.dropWhile { it.id != episode.id }.drop(1).mapNotNull(MediaItems::playable)
        } else {
            emptyList()
        }
        val items = listOf(head) + followOn

        c.setMediaItems(items, /* startIndex = */ 0, episode.positionMs.coerceAtLeast(0))
        c.playbackParameters = PlaybackParameters(settings.defaultSpeed)
        applySpeedFor(episode.feedUrl)
        c.prepare()
        c.play()
    }

    /** Resolve the per-podcast speed override (falling back to the global default). */
    private fun applySpeedFor(feedUrl: String) {
        scope.launch {
            val speed = repository.getPodcastOnce(feedUrl)?.overrideSpeed ?: settings.defaultSpeed
            controller?.playbackParameters = PlaybackParameters(speed)
        }
    }

    /**
     * Play the user-curated queue starting at [startIndex], loading that episode
     * and everything after it into the player so it flows through the queue.
     */
    fun playFromQueue(queue: List<EpisodeEntity>, startIndex: Int) {
        val c = requireController() ?: return
        val start = queue.getOrNull(startIndex) ?: return
        if (c.currentMediaItem?.mediaId == start.id) {
            c.resume()
            return
        }
        // The start episode is pinned to index 0 so its saved position applies to
        // it; later queue items follow. Nothing playable at the start = no-op.
        val head = MediaItems.playable(start) ?: return
        val items = listOf(head) + queue.drop(startIndex + 1).mapNotNull(MediaItems::playable)
        c.setMediaItems(items, /* startIndex = */ 0, start.positionMs.coerceAtLeast(0))
        c.playbackParameters = PlaybackParameters(settings.defaultSpeed)
        applySpeedFor(start.feedUrl)
        c.prepare()
        c.play()
    }

    fun playPause() {
        val c = requireController() ?: return
        if (c.isPlaying) c.pause() else c.resume()
    }

    fun pause() { controller?.pause() }

    /**
     * Mark the currently-loaded episode played and drop it from the Up-Next
     * queue. Used by the end-of-episode sleep timer, which pauses a beat before
     * the true end — without this the episode would never be marked finished and
     * would linger in the queue and "Continue listening" (issue P1-4).
     */
    fun finishCurrentEpisode() {
        val id = controller?.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: return
        scope.launch {
            repository.setPlayed(id, true)
            repository.removeFromQueue(id)
        }
    }

    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs) }

    /** Skip backward by the user-configured interval. */
    fun seekBack() {
        val c = requireController() ?: return
        c.seekTo((c.currentPosition - settings.skipBackMs).coerceAtLeast(0))
    }

    /** Skip forward by the user-configured interval. */
    fun seekForward() {
        val c = requireController() ?: return
        val target = c.currentPosition + settings.skipForwardMs
        val duration = c.duration
        c.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
    }

    /** Advance to the next loaded item (e.g. the next queued episode). */
    fun next() {
        val c = requireController() ?: return
        if (c.hasNextMediaItem()) c.seekToNextMediaItem()
    }

    /** Go to the previous loaded item, or restart the current one. */
    fun previous() {
        val c = requireController() ?: return
        if (c.hasPreviousMediaItem()) c.seekToPreviousMediaItem() else c.seekTo(0)
    }

    fun setSpeed(speed: Float) {
        controller?.playbackParameters = PlaybackParameters(speed)
        scope.launch { settingsRepository.setDefaultSpeed(speed) }
    }

    fun stop() {
        controller?.run {
            pause()
            clearMediaItems()
        }
    }

    /**
     * Resume the already-loaded item. After a playback error the player parks in
     * [Player.STATE_IDLE]; calling [MediaController.play] alone then does nothing
     * and the play button looks dead — re-[prepare] first so it can recover
     * (issue P1-5).
     */
    private fun MediaController.resume() {
        if (playbackState == Player.STATE_IDLE) prepare()
        play()
    }
}
