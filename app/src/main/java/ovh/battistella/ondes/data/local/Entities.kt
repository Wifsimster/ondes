package ovh.battistella.ondes.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class DownloadState { NONE, QUEUED, DOWNLOADING, DOWNLOADED, FAILED }

@Entity(tableName = "podcasts")
data class PodcastEntity(
    @PrimaryKey val feedUrl: String,
    val title: String,
    val author: String,
    val description: String,
    val imageUrl: String,
    val link: String,
    val subscribed: Boolean = true,
    val lastUpdated: Long = 0L,
    /** Per-podcast playback speed override; null = use the global default. */
    val overrideSpeed: Float? = null,
    /** Auto-download newly published episodes for this subscription. */
    @ColumnInfo(defaultValue = "0") val autoDownload: Boolean = false,
    /**
     * Validators from the last successful feed fetch, replayed as
     * `If-None-Match` / `If-Modified-Since` so an unchanged feed answers 304 and
     * costs neither a full download nor a re-parse (opt. 1).
     */
    val etag: String? = null,
    val lastModified: String? = null,
)

@Entity(
    tableName = "episodes",
    indices = [Index("feedUrl"), Index("pubDate"), Index("downloadState")],
)
data class EpisodeEntity(
    @PrimaryKey val id: String,            // feed-scoped id, see [episodeId]
    val feedUrl: String,
    val title: String,
    val description: String,
    val audioUrl: String,
    val imageUrl: String,
    val pubDate: Long,                     // epoch millis
    val durationMs: Long,                  // 0 if unknown
    val positionMs: Long = 0L,             // resume position
    val isPlayed: Boolean = false,
    val isFinished: Boolean = false,
    val downloadState: DownloadState = DownloadState.NONE,
    val localFilePath: String? = null,
    val downloadProgress: Int = 0,         // 0..100
    val chaptersUrl: String? = null,       // Podcasting 2.0 chapters JSON URL
    /** When this episode was last played, for "Continue listening" ordering. */
    @ColumnInfo(defaultValue = "0") val lastPlayedAt: Long = 0L,
)

/**
 * The primary key for an episode, scoped to the feed that published it.
 *
 * A feed GUID is only guaranteed unique *within* its own feed, and plenty of
 * feeds number their items `1`, `2`, `3`. Keyed on the bare GUID, the second
 * subscription to reuse one lost its episode to `INSERT ... IGNORE` — silently,
 * and forever (issue P0-7). Prefixing with the feed URL makes identity
 * per-feed while staying a pure function of the feed data, so it can be
 * recomputed on every refresh without a lookup table.
 */
fun episodeId(feedUrl: String, guid: String): String = "$feedUrl$EPISODE_ID_SEPARATOR$guid"

/** Separator between the feed URL and the per-feed GUID in an episode id. */
const val EPISODE_ID_SEPARATOR = "::"

/** A single chapter marker within an episode. */
data class Chapter(
    val startMs: Long,
    val title: String,
    val imageUrl: String? = null,
)

/** Episode joined with its parent podcast, for list rows. */
data class EpisodeWithPodcast(
    val episode: EpisodeEntity,
    val podcastTitle: String,
    val podcastImageUrl: String,
)

/**
 * One entry in the user-curated "Up Next" queue. [sortIndex] orders the queue
 * (smaller plays first); gaps are left between entries so "Play next" can splice
 * an item in front without renumbering the whole list.
 */
@Entity(tableName = "queue")
data class QueueItemEntity(
    @PrimaryKey val episodeId: String,
    val sortIndex: Long,
)
