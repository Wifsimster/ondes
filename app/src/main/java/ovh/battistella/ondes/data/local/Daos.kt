package ovh.battistella.ondes.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A subscribed podcast plus its count of not-yet-finished episodes. */
data class PodcastWithCount(
    @Embedded val podcast: PodcastEntity,
    val unplayedCount: Int,
)

@Dao
interface PodcastDao {
    @Upsert
    suspend fun upsert(podcast: PodcastEntity)

    @Query("SELECT * FROM podcasts WHERE subscribed = 1 ORDER BY title COLLATE NOCASE ASC")
    fun observeSubscribed(): Flow<List<PodcastEntity>>

    /** Subscriptions with an unplayed-episode count, for library badges & sorting. */
    @Query(
        """
        SELECT p.*, (
            SELECT COUNT(*) FROM episodes e
            WHERE e.feedUrl = p.feedUrl AND e.isFinished = 0
        ) AS unplayedCount
        FROM podcasts p
        WHERE p.subscribed = 1
        ORDER BY p.title COLLATE NOCASE ASC
        """
    )
    fun observeSubscribedWithCounts(): Flow<List<PodcastWithCount>>

    @Query("SELECT * FROM podcasts WHERE feedUrl = :feedUrl")
    fun observePodcast(feedUrl: String): Flow<PodcastEntity?>

    @Query("SELECT * FROM podcasts WHERE feedUrl = :feedUrl")
    suspend fun getPodcast(feedUrl: String): PodcastEntity?

    @Query("UPDATE podcasts SET subscribed = :subscribed WHERE feedUrl = :feedUrl")
    suspend fun setSubscribed(feedUrl: String, subscribed: Boolean)

    @Query("UPDATE podcasts SET overrideSpeed = :speed WHERE feedUrl = :feedUrl")
    suspend fun setOverrideSpeed(feedUrl: String, speed: Float?)

    @Query("UPDATE podcasts SET autoDownload = :enabled WHERE feedUrl = :feedUrl")
    suspend fun setAutoDownload(feedUrl: String, enabled: Boolean)

    @Query("DELETE FROM podcasts WHERE feedUrl = :feedUrl")
    suspend fun delete(feedUrl: String)

    @Query("SELECT * FROM podcasts")
    suspend fun getAll(): List<PodcastEntity>
}

@Dao
interface EpisodeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNew(episodes: List<EpisodeEntity>): List<Long>

    /** Insert-or-replace, used to restore episode playback state from a backup. */
    @Upsert
    suspend fun upsertAll(episodes: List<EpisodeEntity>)

    @Query("SELECT * FROM episodes")
    suspend fun getAll(): List<EpisodeEntity>

    @Update
    suspend fun update(episode: EpisodeEntity)

    @Query("SELECT * FROM episodes WHERE feedUrl = :feedUrl ORDER BY pubDate DESC")
    fun observeForFeed(feedUrl: String): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE id = :id")
    fun observeEpisode(id: String): Flow<EpisodeEntity?>

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun getEpisode(id: String): EpisodeEntity?

    @Query("UPDATE episodes SET positionMs = :positionMs, durationMs = CASE WHEN :durationMs > 0 THEN :durationMs ELSE durationMs END WHERE id = :id")
    suspend fun updatePosition(id: String, positionMs: Long, durationMs: Long)

    // Reset the resume position either way: marking played finishes it, marking
    // unplayed clears progress so an in-progress episode truly starts fresh (and
    // leaves the "Continue listening" row).
    @Query("UPDATE episodes SET isPlayed = :played, isFinished = :played, positionMs = 0 WHERE id = :id")
    suspend fun setPlayed(id: String, played: Boolean)

    @Query("UPDATE episodes SET downloadState = :state, downloadProgress = :progress, localFilePath = :path WHERE id = :id")
    suspend fun updateDownload(id: String, state: DownloadState, progress: Int, path: String?)

    /**
     * Refresh the *feed-owned* content of an already-known episode — title, notes,
     * URLs, dates — while leaving the user's listening progress and download
     * columns untouched. Lets a rotated/signed enclosure URL be picked up without
     * INSERT-IGNORE freezing metadata at first sight, and without ever clobbering a
     * resume position or a downloaded file (issue P1-9).
     */
    @Query(
        """
        UPDATE episodes SET
            title = :title,
            description = :description,
            audioUrl = :audioUrl,
            imageUrl = :imageUrl,
            pubDate = :pubDate,
            durationMs = CASE WHEN :durationMs > 0 THEN :durationMs ELSE durationMs END
        WHERE id = :id
        """
    )
    suspend fun updateContent(
        id: String,
        title: String,
        description: String,
        audioUrl: String,
        imageUrl: String,
        pubDate: Long,
        durationMs: Long,
    )

    /**
     * Restore only the portable listening state for an episode that already
     * exists locally — never the download columns, which are device-local and
     * must survive a backup import (issue P0-8).
     */
    @Query("UPDATE episodes SET positionMs = :positionMs, isPlayed = :isPlayed, isFinished = :isFinished WHERE id = :id")
    suspend fun restoreProgress(id: String, positionMs: Long, isPlayed: Boolean, isFinished: Boolean)

    /** Backfill a chapters URL for an already-known episode that didn't have one. */
    @Query("UPDATE episodes SET chaptersUrl = :url WHERE id = :id AND chaptersUrl IS NULL")
    suspend fun updateChaptersUrl(id: String, url: String)

    /** Continue listening: started-but-not-finished, most recently touched first. */
    @Query(
        """
        SELECT * FROM episodes
        WHERE positionMs > 0 AND isFinished = 0
        ORDER BY pubDate DESC LIMIT 20
        """
    )
    fun observeInProgress(): Flow<List<EpisodeEntity>>

    /** Newest episodes across all subscriptions, for the home feed. */
    @Query(
        """
        SELECT * FROM episodes
        WHERE feedUrl IN (SELECT feedUrl FROM podcasts WHERE subscribed = 1)
        ORDER BY pubDate DESC LIMIT 50
        """
    )
    fun observeLatest(): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE downloadState = 'DOWNLOADED' ORDER BY pubDate DESC")
    fun observeDownloaded(): Flow<List<EpisodeEntity>>
}

@Dao
interface QueueDao {
    /** The queue, resolved to full episodes, in play order. */
    @Query(
        """
        SELECT e.* FROM episodes e
        INNER JOIN queue q ON e.id = q.episodeId
        ORDER BY q.sortIndex ASC
        """
    )
    fun observeQueue(): Flow<List<EpisodeEntity>>

    @Query(
        """
        SELECT e.* FROM episodes e
        INNER JOIN queue q ON e.id = q.episodeId
        ORDER BY q.sortIndex ASC
        """
    )
    suspend fun getQueueOnce(): List<EpisodeEntity>

    @Query("SELECT episodeId FROM queue ORDER BY sortIndex ASC")
    suspend fun getOrderedIds(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM queue WHERE episodeId = :episodeId)")
    suspend fun contains(episodeId: String): Boolean

    @Query("SELECT MAX(sortIndex) FROM queue")
    suspend fun maxSortIndex(): Long?

    @Query("SELECT MIN(sortIndex) FROM queue")
    suspend fun minSortIndex(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: QueueItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<QueueItemEntity>)

    @Query("DELETE FROM queue WHERE episodeId = :episodeId")
    suspend fun remove(episodeId: String)

    @Query("DELETE FROM queue")
    suspend fun clear()
}
