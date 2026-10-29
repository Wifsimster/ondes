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
    val overrideSpeed: Float? = null,
    @ColumnInfo(defaultValue = "0") val autoDownload: Boolean = false,
    val etag: String? = null,
    val lastModified: String? = null,
)

@Entity(
    tableName = "episodes",
    indices = [Index("feedUrl"), Index("pubDate"), Index("downloadState")],
)
data class EpisodeEntity(
    @PrimaryKey val id: String,
    val feedUrl: String,
    val title: String,
    val description: String,
    val audioUrl: String,
    val imageUrl: String,
    val pubDate: Long,
    val durationMs: Long,
    val positionMs: Long = 0L,
    val isPlayed: Boolean = false,
    val isFinished: Boolean = false,
    val downloadState: DownloadState = DownloadState.NONE,
    val localFilePath: String? = null,
    val downloadProgress: Int = 0,
    val chaptersUrl: String? = null,
    @ColumnInfo(defaultValue = "0") val lastPlayedAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val pendingNotification: Boolean = false,
)

fun episodeId(feedUrl: String, guid: String): String = "$feedUrl$EPISODE_ID_SEPARATOR$guid"

const val EPISODE_ID_SEPARATOR = "::"

data class Chapter(
    val startMs: Long,
    val title: String,
    val imageUrl: String? = null,
)

data class EpisodeWithPodcast(
    val episode: EpisodeEntity,
    val podcastTitle: String,
    val podcastImageUrl: String,
)

@Entity(tableName = "queue")
data class QueueItemEntity(
    @PrimaryKey val episodeId: String,
    val sortIndex: Long,
)
