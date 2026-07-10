package ovh.battistella.ondes.playback

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionResult
import ovh.battistella.ondes.MainActivity
import ovh.battistella.ondes.data.repository.PodcastRepository
import ovh.battistella.ondes.data.settings.OndesSettings
import ovh.battistella.ondes.data.settings.SettingsRepository
import ovh.battistella.ondes.download.DownloadManager
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Foreground media service that owns the ExoPlayer + MediaSession. Provides
 * background playback, lock-screen / notification transport controls, and
 * persistence of the resume position back into the database.
 *
 * Implemented as a [MediaLibraryService] so the app exposes a browsable
 * content hierarchy (Continue listening / Subscriptions / Downloads) to
 * Android Auto, Wear OS and other `MediaBrowser` clients.
 */
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject lateinit var repository: PodcastRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var downloadManager: DownloadManager
    @Inject lateinit var libraryTree: MediaLibraryTree

    private var mediaSession: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionSaver: Job? = null

    /** Volume-boost effect, (re)bound to the player's current audio session. */
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var loudnessSessionId: Int = C.AUDIO_SESSION_ID_UNSET

    /** Latest settings snapshot, kept current for auto-delete decisions. */
    @Volatile private var settings: OndesSettings = OndesSettings()

    /** The item currently playing, tracked so we can mark it finished on transition. */
    private var playingMediaId: String? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate: media service starting")

        // Read the persisted settings once so the notification / Android Auto
        // skip buttons honour the user's chosen intervals from launch. Bounded by
        // a timeout so a slow DataStore read can never block onCreate (and the main
        // thread the browse callbacks would otherwise queue behind) indefinitely —
        // falling back to defaults, which the collector below promptly corrects.
        settings = runBlocking {
            withTimeoutOrNull(SETTINGS_LOAD_TIMEOUT_MS) { settingsRepository.settings.first() }
        } ?: OndesSettings()
        scope.launch {
            settingsRepository.settings.collect {
                settings = it
                applyAudioEffects()
            }
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(settings.skipBackMs)
            .setSeekForwardIncrementMs(settings.skipForwardMs)
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) startPositionSaver() else {
                    persistPosition()
                    positionSaver?.cancel()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // An automatic transition means the previous episode played out
                // to the end — mark it finished before moving on.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    playingMediaId?.let(::markFinished)
                }
                persistPosition()
                playingMediaId = mediaItem?.mediaId?.takeIf { it.isNotBlank() }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) markCurrentFinished()
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                applyAudioEffects()
            }
        })

        applyAudioEffects()
        mediaSession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            // Wire the media notification (and lock-screen / "island" capsule) to
            // reopen the app when tapped or expanded. Media3 uses the session
            // activity as the notification's content intent; without it a tap is a
            // no-op. singleTop keeps the running instance instead of recreating it.
            .setSessionActivity(openAppIntent())
            .build()
    }

    /** PendingIntent that brings [MainActivity] to the foreground when the media notification is tapped. */
    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            // NEW_TASK so the tap opens the app even when its task no longer
            // exists (process killed while the media notification lingers);
            // SINGLE_TOP reuses the running instance. Mirrors NewEpisodeNotifier.
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    /** Apply the user's skip-silence and volume-boost preferences to the player. */
    private fun applyAudioEffects() {
        if (!::player.isInitialized) return
        player.skipSilenceEnabled = settings.skipSilence

        val sessionId = player.audioSessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        try {
            if (loudnessEnhancer == null || loudnessSessionId != sessionId) {
                loudnessEnhancer?.release()
                loudnessEnhancer = LoudnessEnhancer(sessionId)
                loudnessSessionId = sessionId
            }
            loudnessEnhancer?.apply {
                setTargetGain(if (settings.boostVolume) BOOST_GAIN_MB else 0)
                setEnabled(settings.boostVolume)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "LoudnessEnhancer unavailable for session $sessionId", t)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        Log.i(TAG, "onGetSession: controller='${controllerInfo.packageName}' uid=${controllerInfo.uid} session=${mediaSession != null}")
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // Keep playing in the background if media is active; otherwise stop.
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy: media service stopping")
        persistPosition()
        positionSaver?.cancel()
        scope.cancel()
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // Android Auto / MediaBrowser content tree
    // ---------------------------------------------------------------------

    /**
     * Thin bridge from the Media3 session callbacks to [MediaLibraryTree], which
     * owns the actual browse content. The callback layer keeps only the cross-
     * cutting plumbing: the untrusted-caller gate, coroutine/future bridging, and
     * mapping results/exceptions onto [LibraryResult].
     */
    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future(Dispatchers.IO) {
            if (!libraryTree.isCallerTrusted(browser)) {
                Log.w(TAG, "onGetLibraryRoot: denied untrusted '${browser.packageName}' uid=${browser.uid}")
                return@future LibraryResult.ofError(SessionResult.RESULT_ERROR_PERMISSION_DENIED)
            }
            runCatching {
                // Android Auto asks for a "recent" root to populate the resume /
                // now-playing card; the tree hands back a dedicated root for it.
                LibraryResult.ofItem(libraryTree.root(params?.isRecent == true), libraryTree.rootParams())
            }.getOrElse {
                Log.e(TAG, "onGetLibraryRoot failed", it)
                LibraryResult.ofError(SessionResult.RESULT_ERROR_UNKNOWN)
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future(Dispatchers.IO) {
            if (!libraryTree.isCallerTrusted(browser)) {
                return@future LibraryResult.ofError(SessionResult.RESULT_ERROR_PERMISSION_DENIED)
            }
            runCatching {
                libraryTree.item(mediaId)
                    ?.let { LibraryResult.ofItem(it, null) }
                    ?: LibraryResult.ofError(SessionResult.RESULT_ERROR_BAD_VALUE)
            }.getOrElse {
                Log.e(TAG, "onGetItem failed for '$mediaId'", it)
                LibraryResult.ofError(SessionResult.RESULT_ERROR_UNKNOWN)
            }
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
            if (!libraryTree.isCallerTrusted(browser)) {
                return@future LibraryResult.ofItemList(ImmutableList.of<MediaItem>(), null)
            }
            runCatching {
                LibraryResult.ofItemList(ImmutableList.copyOf(libraryTree.children(parentId)), null)
            }.getOrElse {
                Log.e(TAG, "onGetChildren failed for '$parentId'", it)
                LibraryResult.ofItemList(ImmutableList.of<MediaItem>(), null)
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = scope.future(Dispatchers.IO) {
            libraryTree.resolve(mediaItems)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaItemsWithStartPosition> = scope.future(Dispatchers.IO) {
            libraryTree.setMediaItems(mediaItems, startIndex, startPositionMs)
        }
    }

    // ---------------------------------------------------------------------
    // Position persistence
    // ---------------------------------------------------------------------

    private fun startPositionSaver() {
        positionSaver?.cancel()
        positionSaver = scope.launch {
            while (isActive) {
                delay(POSITION_SAVE_INTERVAL_MS)
                persistPosition()
            }
        }
    }

    private fun persistPosition() {
        val item = player.currentMediaItem ?: return
        val id = item.mediaId
        if (id.isBlank()) return
        val position = player.currentPosition.coerceAtLeast(0)
        val duration = player.duration.let { if (it == C.TIME_UNSET) 0L else it }
        scope.launch(Dispatchers.IO) {
            repository.savePosition(id, position, duration)
        }
    }

    private fun markCurrentFinished() {
        val id = player.currentMediaItem?.mediaId ?: return
        markFinished(id)
    }

    /** Mark an episode played and, if the user opted in, delete its download. */
    private fun markFinished(id: String) {
        if (id.isBlank()) return
        scope.launch(Dispatchers.IO) {
            repository.setPlayed(id, true)
            // A finished episode leaves the Up-Next queue so it stays current.
            repository.removeFromQueue(id)
            if (settings.autoDeleteFinished) {
                repository.getEpisode(id)?.let { episode ->
                    // Automatic cleanup — no "Undo" snackbar.
                    downloadManager.deleteDownload(episode.id, episode.localFilePath, showUndo = false)
                }
            }
        }
    }

    companion object {
        /** Logcat tag for Android Auto / MediaBrowser diagnostics: `adb logcat -s OndesAuto`. */
        private const val TAG = "OndesAuto"

        private const val POSITION_SAVE_INTERVAL_MS = 5_000L

        /** Upper bound on the blocking settings read in onCreate. */
        private const val SETTINGS_LOAD_TIMEOUT_MS = 2_000L

        /** Volume-boost gain in millibels (~10 dB) when "Boost volume" is on. */
        private const val BOOST_GAIN_MB = 1_000
    }
}
