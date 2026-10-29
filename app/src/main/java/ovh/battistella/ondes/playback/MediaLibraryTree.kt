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

@Singleton
class MediaLibraryTree @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: PodcastRepository,
) {

    fun isCallerTrusted(caller: MediaSession.ControllerInfo): Boolean {
        if (caller.uid == Process.myUid() || caller.uid == Process.SYSTEM_UID) return true
        return caller.packageName in TRUSTED_BROWSER_PACKAGES
    }

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

    suspend fun item(mediaId: String): MediaItem? = when (mediaId) {
        ROOT_ID -> root(isRecent = false)
        RECENT_ROOT_ID -> root(isRecent = true)
        CONTINUE_ID -> MediaItems.folder(CONTINUE_ID, context.getString(R.string.continue_listening))
        SUBSCRIPTIONS_ID ->
            MediaItems.folder(SUBSCRIPTIONS_ID, context.getString(R.string.subscriptions_title))
        DOWNLOADS_ID -> MediaItems.folder(DOWNLOADS_ID, context.getString(R.string.nav_downloads))
        else -> when {
            mediaId.startsWith(PODCAST_PREFIX) ->
                repository.getPodcastOnce(mediaId.removePrefix(PODCAST_PREFIX))?.let(::podcastItem)
            else -> repository.getEpisode(mediaId)?.let(MediaItems::browsable)
        }
    }

    suspend fun children(parentId: String): List<MediaItem> = when (parentId) {
        ROOT_ID -> listOf(
            MediaItems.folder(CONTINUE_ID, context.getString(R.string.continue_listening)),
            MediaItems.folder(SUBSCRIPTIONS_ID, context.getString(R.string.subscriptions_title)),
            MediaItems.folder(DOWNLOADS_ID, context.getString(R.string.nav_downloads)),
        )
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

    suspend fun resolve(items: List<MediaItem>): List<MediaItem> =
        resolveIndexed(items).map { it.second }

    private suspend fun resolveIndexed(items: List<MediaItem>): List<Pair<Int, MediaItem>> {
        val resolved = items.mapIndexedNotNull { index, item ->
            if (item.localConfiguration != null) return@mapIndexedNotNull index to item
            repository.getEpisode(item.mediaId)?.let(MediaItems::playable)?.let { index to it }
        }
        Log.i(TAG, "resolve: ${items.size} requested -> ${resolved.size} playable")
        return resolved
    }

    suspend fun setMediaItems(
        items: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): MediaItemsWithStartPosition {
        val indexed = resolveIndexed(items)
        if (indexed.isEmpty()) return MediaItemsWithStartPosition(emptyList(), 0, 0L)
        val resolved = indexed.map { it.second }
        val requested = if (startIndex == C.INDEX_UNSET) 0 else startIndex
        val index = indexed.indexOfFirst { it.first >= requested }
            .takeIf { it >= 0 } ?: resolved.lastIndex
        val pinnedStillValid = indexed[index].first == requested
        val position = if (startPositionMs != C.TIME_UNSET && pinnedStillValid) {
            startPositionMs
        } else {
            repository.getEpisode(resolved[index].mediaId)
                ?.let { PlaybackTransitions.resumeTargetMs(it.positionMs, it.isFinished) } ?: 0L
        }
        return MediaItemsWithStartPosition(resolved, index, position)
    }

    suspend fun resumption(): MediaItemsWithStartPosition {
        val queue = repository.getQueueOnce()
        val mostRecent = repository.getInProgressOnce().firstOrNull()
        val ordered = when (mostRecent) {
            null -> queue
            else -> listOf(mostRecent) + queue.filterNot { it.id == mostRecent.id }
        }
        val playable = ordered.mapNotNull { episode ->
            MediaItems.playable(episode)?.let { episode to it }
        }
        val head = playable.firstOrNull()
            ?: return MediaItemsWithStartPosition(emptyList(), 0, 0L)
        Log.i(TAG, "resumption: ${playable.size} items, head='${head.first.title}'")
        return MediaItemsWithStartPosition(
            playable.map { it.second },
            0,
            head.first.positionMs.coerceAtLeast(0),
        )
    }

    private fun appName(): String = context.getString(R.string.app_name)

    private fun podcastItem(podcast: PodcastEntity): MediaItem =
        MediaItems.podcast(PODCAST_PREFIX + podcast.feedUrl, podcast.title, podcast.imageUrl)

    companion object {
        private const val TAG = "OndesAuto"

        private const val ROOT_ID = "[root]"
        private const val RECENT_ROOT_ID = "[recent]"
        private const val CONTINUE_ID = "[continue]"
        private const val SUBSCRIPTIONS_ID = "[subscriptions]"
        private const val DOWNLOADS_ID = "[downloads]"
        private const val PODCAST_PREFIX = "[podcast]"

        private val TRUSTED_BROWSER_PACKAGES = setOf(
            "com.google.android.projection.gearhead",
            "com.google.android.autosimulator",
            "com.google.android.carassistant",
            "com.google.android.googlequicksearchbox",
            "com.google.android.wearable.app",
            "com.android.systemui",
            "com.google.android.bluetooth",
        )
    }
}
