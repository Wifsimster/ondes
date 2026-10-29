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

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `episodes` ADD COLUMN `chaptersUrl` TEXT")
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `podcasts` ADD COLUMN `overrideSpeed` REAL")
            db.execSQL("ALTER TABLE `podcasts` ADD COLUMN `autoDownload` INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_episodes_downloadState` " +
                    "ON `episodes` (`downloadState`)"
            )
        }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
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
            db.execSQL("ALTER TABLE `podcasts` ADD COLUMN `etag` TEXT")
            db.execSQL("ALTER TABLE `podcasts` ADD COLUMN `lastModified` TEXT")
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `episodes` ADD COLUMN `pendingNotification` " +
                    "INTEGER NOT NULL DEFAULT 0"
            )
        }
    }
}
