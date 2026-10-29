package ovh.battistella.ondes.playback

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionResult
import ovh.battistella.ondes.MainActivity
import ovh.battistella.ondes.R
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

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

    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var loudnessSessionId: Int = C.AUDIO_SESSION_ID_UNSET

    @Volatile private var settings: OndesSettings = OndesSettings()

    private var playingMediaId: String? = null

    private var pendingResumeId: String? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate: media service starting")

        settings = runBlocking {
            withTimeoutOrNull(SETTINGS_LOAD_TIMEOUT_MS) { settingsRepository.settings.first() }
        } ?: OndesSettings()
        scope.launch {
            settingsRepository.settings.collect {
                settings = it
                applyAudioEffects()
                applyMediaButtonPreferences()
            }
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
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

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (reason != Player.DISCONTINUITY_REASON_SEEK &&
                    reason != Player.DISCONTINUITY_REASON_REMOVE
                ) return
                val oldId = oldPosition.mediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: return
                if (oldId != newPosition.mediaItem?.mediaId) {
                    persistPositionFor(oldId, oldPosition.positionMs)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    playingMediaId?.let(::markFinished)
                }
                val newId = mediaItem?.mediaId?.takeIf { it.isNotBlank() }
                playingMediaId = newId
                if (newId != null &&
                    (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                        reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
                ) {
                    pendingResumeId = newId
                    resumeIncomingAtSavedPosition(newId)
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) markCurrentFinished()
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                applyAudioEffects()
            }
        })

        applyAudioEffects()

        val sessionPlayer = object : ForwardingPlayer(player) {
            override fun getSeekBackIncrement(): Long = settings.skipBackMs.coerceAtLeast(1)
            override fun getSeekForwardIncrement(): Long = settings.skipForwardMs.coerceAtLeast(1)

            override fun seekBack() {
                seekTo((currentPosition - settings.skipBackMs).coerceAtLeast(0))
            }

            override fun seekForward() {
                val d = duration
                val target = currentPosition + settings.skipForwardMs
                seekTo(if (d != C.TIME_UNSET && d > 0) target.coerceAtMost(d) else target)
            }
        }
        mediaSession = MediaLibrarySession.Builder(this, sessionPlayer, LibraryCallback())
            .setMediaButtonPreferences(mediaButtonPreferences())
            .setSessionActivity(openAppIntent())
            .build()
    }

    private fun mediaButtonPreferences(): List<CommandButton> = listOf(
        CommandButton.Builder(skipBackIcon(settings.skipBackMs))
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setDisplayName(
                getString(R.string.skip_back_secs, (settings.skipBackMs / 1_000L).toInt()),
            )
            .build(),
        CommandButton.Builder(skipForwardIcon(settings.skipForwardMs))
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setDisplayName(
                getString(R.string.skip_forward_secs, (settings.skipForwardMs / 1_000L).toInt()),
            )
            .build(),
    )

    private fun applyMediaButtonPreferences() {
        mediaSession?.setMediaButtonPreferences(mediaButtonPreferences())
    }

    private fun skipBackIcon(ms: Long): Int = when (nearestSkipSeconds(ms)) {
        5 -> CommandButton.ICON_SKIP_BACK_5
        10 -> CommandButton.ICON_SKIP_BACK_10
        15 -> CommandButton.ICON_SKIP_BACK_15
        else -> CommandButton.ICON_SKIP_BACK_30
    }

    private fun skipForwardIcon(ms: Long): Int = when (nearestSkipSeconds(ms)) {
        5 -> CommandButton.ICON_SKIP_FORWARD_5
        10 -> CommandButton.ICON_SKIP_FORWARD_10
        15 -> CommandButton.ICON_SKIP_FORWARD_15
        else -> CommandButton.ICON_SKIP_FORWARD_30
    }

    private fun nearestSkipSeconds(ms: Long): Int {
        val seconds = (ms / 1_000L).toInt()
        return listOf(5, 10, 15, 30).minByOrNull { kotlin.math.abs(it - seconds) } ?: 30
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, SESSION_ACTIVITY_REQUEST_CODE, intent, flags)
    }

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
        val ended = player.playbackState == Player.STATE_ENDED
        if (!player.playWhenReady || player.mediaItemCount == 0 || ended) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy: media service stopping")
        positionSaver?.cancel()
        saveFinalPositionBlocking()
        scope.cancel()
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        mediaSession?.run {
            release()
        }
        player.release()
        mediaSession = null
        super.onDestroy()
    }

    private fun saveFinalPositionBlocking() {
        val item = player.currentMediaItem ?: return
        val id = item.mediaId
        if (id.isBlank()) return
        val position = player.currentPosition.coerceAtLeast(0)
        val duration = player.duration.let { if (it == C.TIME_UNSET) 0L else it }
        runBlocking { repository.savePosition(id, position, duration) }
    }

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

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaItemsWithStartPosition> = scope.future(Dispatchers.IO) {
            runCatching { libraryTree.resumption() }.getOrElse {
                Log.e(TAG, "onPlaybackResumption failed", it)
                MediaItemsWithStartPosition(emptyList(), 0, 0L)
            }
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
        if (id.isBlank() || id == pendingResumeId) return
        val position = player.currentPosition.coerceAtLeast(0)
        val duration = player.duration.let { if (it == C.TIME_UNSET) 0L else it }
        scope.launch(Dispatchers.IO) {
            repository.savePosition(id, position, duration)
        }
    }

    private fun persistPositionFor(mediaId: String, positionMs: Long) {
        if (mediaId.isBlank()) return
        val position = positionMs.coerceAtLeast(0)
        scope.launch(Dispatchers.IO) {
            repository.savePosition(mediaId, position, 0)
        }
    }

    private fun resumeIncomingAtSavedPosition(mediaId: String) {
        scope.launch {
            try {
                val episode = withContext(Dispatchers.IO) { repository.getEpisode(mediaId) } ?: return@launch
                val target = PlaybackTransitions.resumeTargetMs(episode.positionMs, episode.isFinished)
                    ?: return@launch
                if (player.currentMediaItem?.mediaId == mediaId) {
                    player.seekTo(target)
                }
            } finally {
                if (pendingResumeId == mediaId) pendingResumeId = null
            }
        }
    }

    private fun markCurrentFinished() {
        val id = player.currentMediaItem?.mediaId ?: return
        markFinished(id)
    }

    private fun markFinished(id: String) {
        if (id.isBlank()) return
        scope.launch(Dispatchers.IO) {
            repository.setPlayed(id, true)
            repository.removeFromQueue(id)
            if (settings.autoDeleteFinished) {
                repository.getEpisode(id)?.let { episode ->
                    downloadManager.deleteDownload(episode.id, episode.localFilePath, showUndo = false)
                }
            }
        }
    }

    companion object {
        private const val TAG = "OndesAuto"

        private const val POSITION_SAVE_INTERVAL_MS = 5_000L

        private const val SETTINGS_LOAD_TIMEOUT_MS = 2_000L

        private const val BOOST_GAIN_MB = 1_000

        private const val SESSION_ACTIVITY_REQUEST_CODE = 3_001
    }
}
