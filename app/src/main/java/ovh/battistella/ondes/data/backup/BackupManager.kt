package ovh.battistella.ondes.data.backup

import androidx.room.withTransaction
import ovh.battistella.ondes.data.local.EpisodeDao
import ovh.battistella.ondes.data.local.EpisodeEntity
import ovh.battistella.ondes.data.local.OndesDatabase
import ovh.battistella.ondes.data.local.PodcastDao
import ovh.battistella.ondes.data.local.PodcastEntity
import ovh.battistella.ondes.data.local.QueueDao
import ovh.battistella.ondes.data.local.QueueItemEntity
import ovh.battistella.ondes.data.settings.OndesSettings
import ovh.battistella.ondes.data.settings.SettingsRepository
import ovh.battistella.ondes.data.settings.ThemeMode
import ovh.battistella.ondes.util.httpUrlOrEmpty
import ovh.battistella.ondes.util.isHttpUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Portable playback progress for an episode already present locally. */
private data class RestoredProgress(
    val id: String,
    val positionMs: Long,
    val isPlayed: Boolean,
    val isFinished: Boolean,
)

/**
 * Full, local-first backup & restore. Writes a single JSON document holding the
 * user's subscriptions, per-episode listening progress, queue and settings — so
 * a phone migration loses nothing — and reads it back. Downloads (device-local
 * files) are intentionally not part of a portable backup.
 */
@Singleton
class BackupManager @Inject constructor(
    private val db: OndesDatabase,
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val queueDao: QueueDao,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun export(output: OutputStream) = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("version", BACKUP_VERSION)

        val podcasts = JSONArray()
        podcastDao.getAll().forEach { p ->
            podcasts.put(
                JSONObject()
                    .put("feedUrl", p.feedUrl)
                    .put("title", p.title)
                    .put("author", p.author)
                    .put("description", p.description)
                    .put("imageUrl", p.imageUrl)
                    .put("link", p.link)
                    .put("subscribed", p.subscribed)
                    .put("lastUpdated", p.lastUpdated)
                    .put("overrideSpeed", p.overrideSpeed?.toDouble() ?: JSONObject.NULL)
                    .put("autoDownload", p.autoDownload)
            )
        }
        root.put("podcasts", podcasts)

        val episodes = JSONArray()
        episodeDao.getAll().forEach { e ->
            episodes.put(
                JSONObject()
                    .put("id", e.id)
                    .put("feedUrl", e.feedUrl)
                    .put("title", e.title)
                    .put("description", e.description)
                    .put("audioUrl", e.audioUrl)
                    .put("imageUrl", e.imageUrl)
                    .put("pubDate", e.pubDate)
                    .put("durationMs", e.durationMs)
                    .put("positionMs", e.positionMs)
                    .put("isPlayed", e.isPlayed)
                    .put("isFinished", e.isFinished)
            )
        }
        root.put("episodes", episodes)

        val queue = JSONArray()
        queueDao.getOrderedIds().forEach { queue.put(it) }
        root.put("queue", queue)

        root.put("settings", settingsToJson(settingsRepository.snapshot()))

        output.write(root.toString(2).toByteArray(Charsets.UTF_8))
        output.flush()
    }

    suspend fun import(input: InputStream) = withContext(Dispatchers.IO) {
        // Parse and validate the WHOLE document up front, before touching the
        // database. Previously a missing key threw mid-import (getString), and the
        // writes weren't atomic, leaving the library half-restored with no
        // rollback (issue P0-9). Nothing below can throw on malformed input.
        val root = JSONObject(input.readBytes().toString(Charsets.UTF_8))

        val podcasts = root.optJSONArray("podcasts").objects().mapNotNull { o ->
            // A backup is user-supplied: a tampered file could point a feed or its
            // artwork at a local URI. Skip non-http feeds, strip non-http art.
            val feedUrl = o.optString("feedUrl")
            if (!isHttpUrl(feedUrl)) return@mapNotNull null
            PodcastEntity(
                feedUrl = feedUrl,
                title = o.optString("title"),
                author = o.optString("author"),
                description = o.optString("description"),
                imageUrl = httpUrlOrEmpty(o.optString("imageUrl")),
                link = o.optString("link"),
                subscribed = o.optBoolean("subscribed", true),
                lastUpdated = o.optLong("lastUpdated", 0L),
                overrideSpeed = if (o.isNull("overrideSpeed")) null
                else o.optDouble("overrideSpeed").toFloat().takeIf { it > 0f },
                autoDownload = o.optBoolean("autoDownload", false),
            )
        }

        // New episodes are inserted whole; for episodes that already exist locally
        // only the portable listening state is restored, so an @Upsert can't wipe
        // their download state / chapters (issue P0-8).
        val episodes = root.optJSONArray("episodes").objects().mapNotNull { o ->
            val audioUrl = httpUrlOrEmpty(o.optString("audioUrl"))
            val id = o.optString("id")
            if (id.isEmpty() || audioUrl.isEmpty()) return@mapNotNull null
            EpisodeEntity(
                id = id,
                feedUrl = o.optString("feedUrl"),
                title = o.optString("title"),
                description = o.optString("description"),
                audioUrl = audioUrl,
                imageUrl = httpUrlOrEmpty(o.optString("imageUrl")),
                pubDate = o.optLong("pubDate", 0L),
                durationMs = o.optLong("durationMs", 0L),
                positionMs = o.optLong("positionMs", 0L),
                isPlayed = o.optBoolean("isPlayed", false),
                isFinished = o.optBoolean("isFinished", false),
            )
        }
        val progress = episodes.map {
            RestoredProgress(it.id, it.positionMs, it.isPlayed, it.isFinished)
        }

        val queueItems = root.optJSONArray("queue").let { arr ->
            if (arr == null) emptyList()
            else (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotEmpty() } }
                .mapIndexed { index, id -> QueueItemEntity(id, index.toLong() * QUEUE_STEP) }
        }

        val settings = root.optJSONObject("settings")?.let(::settingsFromJson)

        // Apply all database writes atomically — either the whole restore lands or
        // none of it does.
        db.withTransaction {
            podcasts.forEach { podcastDao.upsert(it) }
            episodeDao.insertNew(episodes)
            progress.forEach { episodeDao.restoreProgress(it.id, it.positionMs, it.isPlayed, it.isFinished) }
            queueDao.clear()
            if (queueItems.isNotEmpty()) queueDao.upsertAll(queueItems)
        }
        // Settings live in DataStore, outside the Room transaction.
        settings?.let { settingsRepository.restore(it) }
    }

    /** Iterate a nullable [JSONArray] of objects, tolerating gaps. */
    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList()
        else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun settingsToJson(s: OndesSettings): JSONObject = JSONObject()
        .put("skipBackMs", s.skipBackMs)
        .put("skipForwardMs", s.skipForwardMs)
        .put("defaultSpeed", s.defaultSpeed.toDouble())
        .put("autoAdvance", s.autoAdvance)
        .put("skipSilence", s.skipSilence)
        .put("boostVolume", s.boostVolume)
        .put("wifiOnlyDownloads", s.wifiOnlyDownloads)
        .put("autoDeleteFinished", s.autoDeleteFinished)
        .put("backgroundRefresh", s.backgroundRefresh)
        .put("newEpisodeNotifications", s.newEpisodeNotifications)
        .put("themeMode", s.themeMode.name)
        .put("dynamicColor", s.dynamicColor)
        .put("onboardingDone", s.onboardingDone)

    private fun settingsFromJson(o: JSONObject): OndesSettings {
        val defaults = OndesSettings()
        return OndesSettings(
            skipBackMs = o.optLong("skipBackMs", defaults.skipBackMs),
            skipForwardMs = o.optLong("skipForwardMs", defaults.skipForwardMs),
            defaultSpeed = o.optDouble("defaultSpeed", defaults.defaultSpeed.toDouble()).toFloat(),
            autoAdvance = o.optBoolean("autoAdvance", defaults.autoAdvance),
            skipSilence = o.optBoolean("skipSilence", defaults.skipSilence),
            boostVolume = o.optBoolean("boostVolume", defaults.boostVolume),
            wifiOnlyDownloads = o.optBoolean("wifiOnlyDownloads", defaults.wifiOnlyDownloads),
            autoDeleteFinished = o.optBoolean("autoDeleteFinished", defaults.autoDeleteFinished),
            backgroundRefresh = o.optBoolean("backgroundRefresh", defaults.backgroundRefresh),
            newEpisodeNotifications =
                o.optBoolean("newEpisodeNotifications", defaults.newEpisodeNotifications),
            themeMode = runCatching { ThemeMode.valueOf(o.optString("themeMode")) }
                .getOrDefault(defaults.themeMode),
            dynamicColor = o.optBoolean("dynamicColor", defaults.dynamicColor),
            // Restoring onboardingDone spares a migrating user the first-run
            // onboarding they've already completed (issue P2).
            onboardingDone = o.optBoolean("onboardingDone", defaults.onboardingDone),
        )
    }

    companion object {
        private const val BACKUP_VERSION = 1
        private const val QUEUE_STEP = 1_000L
    }
}
