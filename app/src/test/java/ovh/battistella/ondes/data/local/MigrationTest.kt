package ovh.battistella.ondes.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ovh.battistella.ondes.di.AppModule
import java.io.File
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var database: OndesDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        context.getDatabasePath(DB_NAME).delete()
    }

    @Test
    fun `migrating from 5 re-keys episodes on their feed and keeps user state`() = runBlocking {
        createDatabaseAt(version = 5) {
            execSQL(
                """
                INSERT INTO podcasts (feedUrl, title, author, description, imageUrl, link,
                    subscribed, lastUpdated, overrideSpeed, autoDownload)
                VALUES ('$FEED_A', 'A', 'a', '', '', '', 1, 10, 1.5, 1)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO episodes (id, feedUrl, title, description, audioUrl, imageUrl,
                    pubDate, durationMs, positionMs, isPlayed, isFinished, downloadState,
                    localFilePath, downloadProgress, chaptersUrl)
                VALUES ('1', '$FEED_A', 'A1', '', 'https://a.example/1.mp3', '',
                    100, 600000, 12345, 0, 0, 'DOWNLOADED', '/data/a1.audio', 100, NULL)
                """.trimIndent()
            )
            execSQL("INSERT INTO queue (episodeId, sortIndex) VALUES ('1', 1000)")
        }

        val db = openMigratedDatabase(AppModule.MIGRATION_5_6, AppModule.MIGRATION_6_7)

        val migrated = db.episodeDao().getEpisode(episodeId(FEED_A, "1"))
        assertEquals("A1", migrated?.title)
        assertEquals(12345L, migrated?.positionMs)
        assertEquals("/data/a1.audio", migrated?.localFilePath)
        assertNull(db.episodeDao().getEpisode("1"))

        assertEquals(listOf(episodeId(FEED_A, "1")), db.queueDao().getOrderedIds())
        assertEquals(listOf(episodeId(FEED_A, "1")), db.queueDao().getQueueOnce().map { it.id })

        val podcast = db.podcastDao().getPodcast(FEED_A)
        assertEquals(1.5f, podcast?.overrideSpeed)
        assertTrue(podcast?.autoDownload == true)
        assertNull(podcast?.etag)
        assertNull(podcast?.lastModified)
    }

    @Test
    fun `after migrating two feeds can each keep their episode 1`() = runBlocking {
        createDatabaseAt(version = 5) {
            execSQL(
                """
                INSERT INTO podcasts (feedUrl, title, author, description, imageUrl, link,
                    subscribed, lastUpdated, overrideSpeed, autoDownload)
                VALUES ('$FEED_A', 'A', 'a', '', '', '', 1, 10, NULL, 0)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO episodes (id, feedUrl, title, description, audioUrl, imageUrl,
                    pubDate, durationMs, positionMs, isPlayed, isFinished, downloadState,
                    localFilePath, downloadProgress, chaptersUrl)
                VALUES ('1', '$FEED_A', 'A1', '', 'https://a.example/1.mp3', '',
                    100, 0, 0, 0, 0, 'NONE', NULL, 0, NULL)
                """.trimIndent()
            )
        }

        val db = openMigratedDatabase(AppModule.MIGRATION_5_6, AppModule.MIGRATION_6_7)
        db.episodeDao().insertNew(
            listOf(
                EpisodeEntity(
                    id = episodeId(FEED_B, "1"),
                    feedUrl = FEED_B,
                    title = "B1",
                    description = "",
                    audioUrl = "https://b.example/1.mp3",
                    imageUrl = "",
                    pubDate = 100,
                    durationMs = 0,
                )
            )
        )

        assertEquals("A1", db.episodeDao().getEpisode(episodeId(FEED_A, "1"))?.title)
        assertEquals("B1", db.episodeDao().getEpisode(episodeId(FEED_B, "1"))?.title)
    }

    @Test
    fun `migrating from 6 leaves the existing episodes unannounced`() = runBlocking {
        createDatabaseAt(version = 6) {
            execSQL(
                """
                INSERT INTO podcasts (feedUrl, title, author, description, imageUrl, link,
                    subscribed, lastUpdated, overrideSpeed, autoDownload, etag, lastModified)
                VALUES ('$FEED_A', 'A', 'a', '', '', '', 1, 10, NULL, 0, NULL, NULL)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO episodes (id, feedUrl, title, description, audioUrl, imageUrl,
                    pubDate, durationMs, positionMs, isPlayed, isFinished, downloadState,
                    localFilePath, downloadProgress, chaptersUrl, lastPlayedAt)
                VALUES ('$FEED_A::1', '$FEED_A', 'A1', '', 'https://a.example/1.mp3', '',
                    100, 0, 0, 0, 0, 'NONE', NULL, 0, NULL, 0)
                """.trimIndent()
            )
        }

        val db = openMigratedDatabase(AppModule.MIGRATION_6_7)

        assertFalse(db.episodeDao().getEpisode(episodeId(FEED_A, "1"))!!.pendingNotification)
        assertEquals(emptyList<EpisodeEntity>(), db.episodeDao().getPendingNotification())
    }

    private fun createDatabaseAt(version: Int, populate: SQLiteDatabase.() -> Unit) {
        val file: File = context.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()
        file.delete()
        val schema = JSONObject(readSchema(version)).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").withTable(table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").withTable(table))
                }
            }
            db.populate()
            db.version = schema.getInt("version")
        }
    }

    private fun openMigratedDatabase(vararg migrations: Migration): OndesDatabase {
        val directExecutor = Executor { it.run() }
        return Room.databaseBuilder(context, OndesDatabase::class.java, DB_NAME)
            .addMigrations(*migrations)
            .allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()
            .also { database = it }
    }

    private fun readSchema(version: Int): String {
        val path = "${OndesDatabase::class.java.canonicalName}/$version.json"
        return checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "Missing exported schema $path — is app/schemas on the test classpath?"
        }.bufferedReader().use { it.readText() }
    }

    private fun String.withTable(table: String) = replace("\${TABLE_NAME}", table)

    private companion object {
        const val DB_NAME = "migration-test.db"
        const val FEED_A = "https://a.example/feed"
        const val FEED_B = "https://b.example/feed"
    }
}
