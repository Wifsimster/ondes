package ovh.battistella.ondes.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter
    fun toDownloadState(value: String?): DownloadState =
        value?.let { runCatching { DownloadState.valueOf(it) }.getOrNull() } ?: DownloadState.NONE

    @TypeConverter
    fun fromDownloadState(state: DownloadState): String = state.name
}

/**
 * Schemas are exported to `app/schemas` and checked in: they are what
 * `MigrationTest` validates each migration against, so a schema change that
 * ships without a migration fails the build's tests instead of silently wiping
 * a user's library at runtime (issue P1-10).
 */
@Database(
    entities = [PodcastEntity::class, EpisodeEntity::class, QueueItemEntity::class],
    version = 7,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class OndesDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun queueDao(): QueueDao
}
