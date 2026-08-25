package ovh.battistella.ondes.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ovh.battistella.ondes.data.local.OndesDatabase
import ovh.battistella.ondes.data.local.EpisodeDao
import ovh.battistella.ondes.data.local.PodcastDao
import ovh.battistella.ondes.data.local.QueueDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * The dispatcher repositories offload blocking I/O (network, disk, DB) onto.
     * Injected rather than hard-coded so tests can pin it to a deterministic test
     * scheduler.
     */
    @Provides
    @Singleton
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): OndesDatabase =
        Room.databaseBuilder(context, OndesDatabase::class.java, "ondes.db")
            // Real migrations preserve the user's library & listening history
            // across schema bumps. There is deliberately NO destructive fallback:
            // it turned a forgotten migration into a silent, total wipe of every
            // subscription and resume position (issue P1-10). Without it such a
            // mistake is a loud crash — and MigrationTest catches it in CI first.
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
            )
            .build()

    @Provides
    fun providePodcastDao(db: OndesDatabase): PodcastDao = db.podcastDao()

    @Provides
    fun provideEpisodeDao(db: OndesDatabase): EpisodeDao = db.episodeDao()

    @Provides
    fun provideQueueDao(db: OndesDatabase): QueueDao = db.queueDao()

    /** v2 adds the persistent Up-Next queue table. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `queue` (" +
                    "`episodeId` TEXT NOT NULL, " +
                    "`sortIndex` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`episodeId`))"
            )
        }
    }

    /** v3 adds a nullable chapters-JSON URL to episodes (Podcasting 2.0 chapters). */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `episodes` ADD COLUMN `chaptersUrl` TEXT")
        }
    }

    /** v4 adds per-podcast playback-speed override and auto-download flag. */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `podcasts` ADD COLUMN `overrideSpeed` REAL")
            db.execSQL("ALTER TABLE `podcasts` ADD COLUMN `autoDownload` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** v5 indexes episodes.downloadState so the Downloads query stops table-scanning. */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_episodes_downloadState` " +
                    "ON `episodes` (`downloadState`)"
            )
        }
    }

    /**
     * v6 re-keys episodes on their *feed-scoped* id, adds the "last played"
     * stamp, and stores the feed's HTTP validators.
     *
     * Episode ids move from the bare feed GUID to `feedUrl::guid` (issue P0-7).
     * GUIDs are only unique within a feed, so two subscriptions that both number
     * their items `1`, `2`, `3` collided and the second feed's episodes were
     * dropped by INSERT-IGNORE. The table is rebuilt rather than UPDATEd in
     * place so the new ids can't transiently collide with old ones mid-statement,
     * and the queue is re-pointed first, while the old ids are still resolvable.
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Re-point the queue while `episodes` still holds the old ids.
            db.execSQL(
                "UPDATE `queue` SET `episodeId` = (" +
                    "SELECT e.`feedUrl` || '::' || e.`id` FROM `episodes` e " +
                    "WHERE e.`id` = `queue`.`episodeId`) " +
                    "WHERE `episodeId` IN (SELECT `id` FROM `episodes`)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `episodes_new` (" +
                    "`id` TEXT NOT NULL, " +
                    "`feedUrl` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, " +
                    "`description` TEXT NOT NULL, " +
                    "`audioUrl` TEXT NOT NULL, " +
                    "`imageUrl` TEXT NOT NULL, " +
                    "`pubDate` INTEGER NOT NULL, " +
                    "`durationMs` INTEGER NOT NULL, " +
                    "`positionMs` INTEGER NOT NULL, " +
                    "`isPlayed` INTEGER NOT NULL, " +
                    "`isFinished` INTEGER NOT NULL, " +
                    "`downloadState` TEXT NOT NULL, " +
                    "`localFilePath` TEXT, " +
                    "`downloadProgress` INTEGER NOT NULL, " +
                    "`chaptersUrl` TEXT, " +
                    "`lastPlayedAt` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`id`))"
            )
            // OR IGNORE: only a feed URL containing the separator could make two
            // distinct rows map onto one id — keep the first rather than abort
            // the migration (and, without a destructive fallback, the app).
            db.execSQL(
                "INSERT OR IGNORE INTO `episodes_new` (" +
                    "`id`, `feedUrl`, `title`, `description`, `audioUrl`, `imageUrl`, " +
                    "`pubDate`, `durationMs`, `positionMs`, `isPlayed`, `isFinished`, " +
                    "`downloadState`, `localFilePath`, `downloadProgress`, `chaptersUrl`, " +
                    "`lastPlayedAt`) " +
                    "SELECT `feedUrl` || '::' || `id`, `feedUrl`, `title`, `description`, " +
                    "`audioUrl`, `imageUrl`, `pubDate`, `durationMs`, `positionMs`, " +
                    "`isPlayed`, `isFinished`, `downloadState`, `localFilePath`, " +
                    "`downloadProgress`, `chaptersUrl`, 0 FROM `episodes`"
            )
            db.execSQL("DROP TABLE `episodes`")
            db.execSQL("ALTER TABLE `episodes_new` RENAME TO `episodes`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_feedUrl` ON `episodes` (`feedUrl`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_pubDate` ON `episodes` (`pubDate`)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_episodes_downloadState` " +
                    "ON `episodes` (`downloadState`)"
            )
            // Conditional-GET validators for the feed refresh (opt. 1).
            db.execSQL("ALTER TABLE `podcasts` ADD COLUMN `etag` TEXT")
            db.execSQL("ALTER TABLE `podcasts` ADD COLUMN `lastModified` TEXT")
        }
    }

    /**
     * v7 remembers, per episode, whether a "new episode" notification still owes
     * the user an announcement.
     *
     * Existing rows default to 0: they are the back catalogue, already seen, and
     * flagging them would turn the first refresh after an update into a wall of
     * notifications.
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `episodes` ADD COLUMN `pendingNotification` " +
                    "INTEGER NOT NULL DEFAULT 0"
            )
        }
    }
}
