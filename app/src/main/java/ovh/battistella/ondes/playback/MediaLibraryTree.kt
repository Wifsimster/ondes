package ovh.battistella.ondes.playback

import android.content.Context
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import dagger.hilt.android.qualifiers.ApplicationContext
import ovh.battistella.ondes.R
import ovh.battistella.ondes.data.local.PodcastEntity
import ovh.battistella.ondes.data.repository.PodcastRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The browsable content hierarchy that [PlaybackService] exposes to Android
 * Auto, Wear OS and other `MediaBrowser` clients: a small set of folders
 * (Continue listening / Subscriptions / Downloads), the podcasts under
 * Subscriptions, and the episodes under each podcast.
 *
 * Pulled out of the service so the service is left with just the player +
 * session lifecycle, and the tree's content rules — backed by the repository,
 * built through the shared [MediaItems] factory — live in one focused place.
 * All node ids and the browser allowlist are owned here too.
 */
@Singleton
class MediaLibraryTree @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: PodcastRepository,
) {

    /**
     * The browse tree exposes the user's whole library (subscriptions, episode
     * titles, feed URLs). The service is `exported` so Android Auto / Wear can
     * reach it, but that also lets any installed app bind — so the content tree
     * is gated to our own UI, the platform, and a small allowlist of known media
     * browsers. Untrusted callers may still connect (so transport controls work)
     * but get nothing back from the library callbacks.
     */
    fun isCallerTrusted(caller: MediaSession.ControllerInfo): Boolean {
        if (caller.uid == Process.myUid() || caller.uid == Process.SYSTEM_UID) return true
        return caller.packageName in TRUSTED_BROWSER_PACKAGES
    }

    /**
     * The browse root. Android Auto asks for a dedicated "recent" root to
     * populate its resume / now-playing card; everything else gets the full root.
     */
    fun root(isRecent: Boolean): MediaItem =
        MediaItems.folder(if (isRecent) RECENT_ROOT_ID else ROOT_ID, appName())

    fun rootParams(): LibraryParams =
        LibraryParams.Builder()
            .setExtras(
                Bundle().apply {
                    putInt(
                        MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                        MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
                    )
                    putInt(
                        MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                        MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
                    )
                }
            )
            .build()

    /** Resolve a single browse node by its media id, or null if unknown. */
    suspend fun item(mediaId: String): MediaItem? = when (mediaId) {
        ROOT_ID -> root(isRecent = false)
        RECENT_ROOT_ID -> root(isRecent = true)
        CONTINUE_ID -> MediaItems.folder(CONTINUE_ID, context.getString(R.string.continue_listening))
        SUBSCRIPTIONS_ID ->
            MediaItems.folder(SUBSCRIPTIONS_ID, context.getString(R.string.subscriptions_title))
        DOWNLOADS_ID -> MediaItems.folder(DOWNLOADS_ID, context.getString(R.string.nav_downloads))
        else -> when {
            // Indexed lookup by feed URL (the id is the URL under a prefix) rather
            // than loading every subscription and linear-scanning it (opt. 9).
            mediaId.startsWith(PODCAST_PREFIX) ->
                repository.getPodcastOnce(mediaId.removePrefix(PODCAST_PREFIX))?.let(::podcastItem)
            else -> repository.getEpisode(mediaId)?.let(MediaItems::browsable)
        }
    }

    /** The children of a browse node, in display order. */
    suspend fun children(parentId: String): List<MediaItem> = when (parentId) {
        ROOT_ID -> listOf(
            MediaItems.folder(CONTINUE_ID, context.getString(R.string.continue_listening)),
            MediaItems.folder(SUBSCRIPTIONS_ID, context.getString(R.string.subscriptions_title)),
            MediaItems.folder(DOWNLOADS_ID, context.getString(R.string.nav_downloads)),
        )
        // Resume card: the single most recent in-progress episode, or the
        // newest episode if nothing is part-way through.
        RECENT_ROOT_ID -> listOfNotNull(
            (repository.getInProgressOnce().firstOrNull()
                ?: repository.getLatestOnce().firstOrNull())
                ?.let(MediaItems::browsable),
        )
        CONTINUE_ID -> repository.getInProgressOnce().map(MediaItems::browsable)
        DOWNLOADS_ID -> repository.getDownloadedOnce().map(MediaItems::browsable)
        SUBSCRIPTIONS_ID -> repository.getSubscriptionsOnce().map(::podcastItem)
        else -> if (parentId.startsWith(PODCAST_PREFIX)) {
            repository.getEpisodesOnce(parentId.removePrefix(PODCAST_PREFIX)).map(MediaItems::browsable)
        } else {
            emptyList()
        }
    }.also { Log.i(TAG, "children: parent='$parentId' -> ${it.size} items") }

    /**
     * Browser clients (Android Auto, notifications) hand us items that carry only
     * a `mediaId`. Resolve each one back to a fully-formed, playable [MediaItem]
     * with its audio URI and metadata, dropping any that have no playable source.
     */
    suspend fun resolve(items: List<MediaItem>): List<MediaItem> {
        val resolved = items.mapNotNull { item ->
            // Already complete (has a URI) — keep as-is.
            if (item.localConfiguration != null) return@mapNotNull item
            repository.getEpisode(item.mediaId)?.let(MediaItems::playable)
        }
        Log.i(TAG, "resolve: ${items.size} requested -> ${resolved.size} playable")
        return resolved
    }

    /**
     * Resolve a "set media items" request from a browser, defaulting the start
     * position to the episode's saved resume point when the caller didn't pin one
     * (e.g. tapping an episode in the car).
     */
    suspend fun setMediaItems(
        items: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): MediaItemsWithStartPosition {
        val resolved = resolve(items)
        if (resolved.isEmpty()) return MediaItemsWithStartPosition(emptyList(), 0, 0L)
        val index = (if (startIndex == C.INDEX_UNSET) 0 else startIndex).coerceIn(0, resolved.lastIndex)
        val position = if (startPositionMs != C.TIME_UNSET) {
            startPositionMs
        } else {
            repository.getEpisode(resolved[index].mediaId)?.positionMs?.coerceAtLeast(0) ?: 0L
        }
        return MediaItemsWithStartPosition(resolved, index, position)
    }

    private fun appName(): String = context.getString(R.string.app_name)

    private fun podcastItem(podcast: PodcastEntity): MediaItem =
        MediaItems.podcast(PODCAST_PREFIX + podcast.feedUrl, podcast.title, podcast.imageUrl)

    companion object {
        /** Logcat tag for Android Auto / MediaBrowser diagnostics: `adb logcat -s OndesAuto`. */
        private const val TAG = "OndesAuto"

        // Browse-tree node ids.
        private const val ROOT_ID = "[root]"
        private const val RECENT_ROOT_ID = "[recent]"
        private const val CONTINUE_ID = "[continue]"
        private const val SUBSCRIPTIONS_ID = "[subscriptions]"
        private const val DOWNLOADS_ID = "[downloads]"
        private const val PODCAST_PREFIX = "[podcast]"

        /**
         * Packages allowed to browse the media library tree, beyond callers that
         * already share our uid or the system uid. Covers Android Auto, the
         * Assistant, Wear OS and the platform's media controls.
         */
        private val TRUSTED_BROWSER_PACKAGES = setOf(
            "com.google.android.projection.gearhead", // Android Auto
            "com.google.android.autosimulator",       // Android Auto head-unit simulator
            "com.google.android.carassistant",        // Automotive assistant
            "com.google.android.googlequicksearchbox", // Google Assistant
            "com.google.android.wearable.app",        // Wear OS companion
            "com.android.systemui",                   // System media controls
            "com.google.android.bluetooth",           // Bluetooth media controls
        )
    }
}
