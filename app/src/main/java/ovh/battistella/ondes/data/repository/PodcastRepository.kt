package ovh.battistella.ondes.data.repository

import androidx.room.withTransaction
import ovh.battistella.ondes.data.local.DownloadState
import ovh.battistella.ondes.data.local.EpisodeDao
import ovh.battistella.ondes.data.local.EpisodeEntity
import ovh.battistella.ondes.data.local.OndesDatabase
import ovh.battistella.ondes.data.local.PodcastDao
import ovh.battistella.ondes.data.local.PodcastEntity
import ovh.battistella.ondes.data.local.PodcastWithCount
import ovh.battistella.ondes.data.local.Chapter
import ovh.battistella.ondes.data.local.QueueDao
import ovh.battistella.ondes.data.local.QueueItemEntity
import ovh.battistella.ondes.data.remote.ParsedFeed
import ovh.battistella.ondes.data.remote.PodcastSearchResult
import ovh.battistella.ondes.data.remote.PodcastSearchService
import ovh.battistella.ondes.data.remote.RssParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A subscription plus the episodes that were newly published in the latest
 * refresh, so callers can post one notification per podcast (named, grouped).
 */
data class NewEpisodeBatch(
    val feedUrl: String,
    val podcastTitle: String,
    val episodes: List<EpisodeEntity>,
)

@Singleton
class PodcastRepository @Inject constructor(
    private val db: OndesDatabase,
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val queueDao: QueueDao,
    private val rssParser: RssParser,
    private val searchService: PodcastSearchService,
    private val httpClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher,
) {
    fun observeSubscriptions(): Flow<List<PodcastEntity>> = podcastDao.observeSubscribed()
    fun observeSubscriptionsWithCounts(): Flow<List<PodcastWithCount>> =
        podcastDao.observeSubscribedWithCounts()
    fun observePodcast(feedUrl: String): Flow<PodcastEntity?> = podcastDao.observePodcast(feedUrl)
    fun observeEpisodes(feedUrl: String): Flow<List<EpisodeEntity>> = episodeDao.observeForFeed(feedUrl)
    fun observeEpisode(id: String): Flow<EpisodeEntity?> = episodeDao.observeEpisode(id)
    fun observeLatest(): Flow<List<EpisodeEntity>> = episodeDao.observeLatest()
    fun observeInProgress(): Flow<List<EpisodeEntity>> = episodeDao.observeInProgress()
    fun observeDownloaded(): Flow<List<EpisodeEntity>> = episodeDao.observeDownloaded()

    suspend fun getEpisode(id: String): EpisodeEntity? = episodeDao.getEpisode(id)
    suspend fun getPodcastOnce(feedUrl: String): PodcastEntity? = podcastDao.getPodcast(feedUrl)

    /** Per-podcast playback-speed override (null clears it, falling back to global). */
    suspend fun setPodcastSpeed(feedUrl: String, speed: Float?) =
        podcastDao.setOverrideSpeed(feedUrl, speed)

    suspend fun setPodcastAutoDownload(feedUrl: String, enabled: Boolean) =
        podcastDao.setAutoDownload(feedUrl, enabled)

    // --- one-shot snapshots, used to build the Android Auto browse tree ---
    suspend fun getSubscriptionsOnce(): List<PodcastEntity> = observeSubscriptions().first()
    suspend fun getEpisodesOnce(feedUrl: String): List<EpisodeEntity> = observeEpisodes(feedUrl).first()
    suspend fun getInProgressOnce(): List<EpisodeEntity> = observeInProgress().first()
    suspend fun getDownloadedOnce(): List<EpisodeEntity> = observeDownloaded().first()
    suspend fun getLatestOnce(): List<EpisodeEntity> = observeLatest().first()

    /** Subscribe to a feed by URL, fetching its content. Returns the feed URL. */
    suspend fun subscribe(feedUrl: String): Result<String> = withContext(ioDispatcher) {
        runCatching {
            refreshFeed(feedUrl, markSubscribed = true)
            feedUrl
        }
    }

    suspend fun unsubscribe(feedUrl: String) = withContext(ioDispatcher) {
        podcastDao.setSubscribed(feedUrl, false)
    }

    /** Soft-unsubscribe several feeds at once (library multi-select). */
    suspend fun unsubscribeAll(feedUrls: Collection<String>) = withContext(ioDispatcher) {
        feedUrls.forEach { podcastDao.setSubscribed(it, false) }
    }

    /**
     * Re-flag several feeds as subscribed — the cheap inverse of [unsubscribeAll]
     * used to undo a bulk unsubscribe. Rows are soft-deleted so this needs no
     * network refresh (unlike [subscribe]).
     */
    suspend fun resubscribeAll(feedUrls: Collection<String>) = withContext(ioDispatcher) {
        feedUrls.forEach { podcastDao.setSubscribed(it, true) }
    }

    /**
     * (Re)fetch a feed and reconcile podcast + episodes into the database.
     * Returns the episodes that were genuinely new this refresh (so callers can
     * surface "new episode" notifications); known episodes keep their state.
     */
    suspend fun refreshFeed(feedUrl: String, markSubscribed: Boolean = false): List<EpisodeEntity> =
        withContext(ioDispatcher) {
            val parsed: ParsedFeed = rssParser.fetchAndParse(feedUrl)
            val existing = podcastDao.getPodcast(feedUrl)
            val podcast = PodcastEntity(
                feedUrl = feedUrl,
                title = parsed.title,
                author = parsed.author,
                description = parsed.description,
                imageUrl = parsed.imageUrl.ifEmpty { existing?.imageUrl.orEmpty() },
                link = parsed.link,
                subscribed = markSubscribed || (existing?.subscribed ?: true),
                lastUpdated = System.currentTimeMillis(),
            )
            val fallbackImage = parsed.imageUrl
            val rows = parsed.episodes.map { e ->
                EpisodeEntity(
                    id = e.guid,
                    feedUrl = feedUrl,
                    title = e.title,
                    description = e.description,
                    audioUrl = e.audioUrl,
                    imageUrl = e.imageUrl.ifEmpty { fallbackImage },
                    pubDate = e.pubDate,
                    durationMs = e.durationMs,
                    chaptersUrl = e.chaptersUrl.ifEmpty { null },
                )
            }
            // One transaction for the whole reconcile: podcast + episodes land
            // together or not at all. Previously an interrupted refresh could bump
            // the podcast's lastUpdated with zero episodes stored, later
            // mis-firing "new episode" notifications for the back catalogue, and
            // cost 2+N separate fsync'd writes (issues P1-8, opt. 4).
            db.withTransaction {
                podcastDao.upsert(podcast)
                // INSERT IGNORE keeps existing playback state for known episodes; a
                // rowId of -1 marks a row that already existed and was skipped.
                val rowIds = episodeDao.insertNew(rows)
                rows.forEachIndexed { index, row ->
                    val isNew = rowIds.getOrElse(index) { -1L } != -1L
                    if (!isNew) {
                        // Refresh feed-owned content for a known episode so a
                        // rotated enclosure URL / edited title is picked up, without
                        // disturbing progress or downloads (issue P1-9).
                        episodeDao.updateContent(
                            id = row.id,
                            title = row.title,
                            description = row.description,
                            audioUrl = row.audioUrl,
                            imageUrl = row.imageUrl,
                            pubDate = row.pubDate,
                            durationMs = row.durationMs,
                        )
                    }
                    // Backfill chapters for episodes that pre-date this field
                    // (updateChaptersUrl only fills a currently-null value).
                    row.chaptersUrl?.let { episodeDao.updateChaptersUrl(row.id, it) }
                }
                rows.filterIndexed { index, _ -> rowIds.getOrElse(index) { -1L } != -1L }
            }
        }

    suspend fun refreshAllSubscriptions(feedUrls: List<String>) = withContext(ioDispatcher) {
        feedUrls.forEach { url -> runCatching { refreshFeed(url) } }
    }

    /**
     * Refresh every subscribed feed and return the newly published episodes,
     * skipping a podcast's first-ever fetch so an initial subscribe doesn't
     * spam a notification for the whole back catalogue.
     */
    suspend fun refreshSubscriptionsForNew(): List<NewEpisodeBatch> = withContext(ioDispatcher) {
        val subscriptions = getSubscriptionsOnce()
        subscriptions.mapNotNull { podcast ->
            val firstFetch = podcast.lastUpdated == 0L
            val newEpisodes = runCatching { refreshFeed(podcast.feedUrl) }
                .getOrDefault(emptyList())
                .takeUnless { firstFetch }
                ?: emptyList()
            if (newEpisodes.isEmpty()) return@mapNotNull null
            // Re-read so the notification shows the freshly-refreshed title.
            val title = podcastDao.getPodcast(podcast.feedUrl)?.title ?: podcast.title
            NewEpisodeBatch(podcast.feedUrl, title, newEpisodes)
        }
    }

    /**
     * Fetch and parse Podcasting 2.0 chapters for an episode, if it advertises a
     * chapters JSON URL. Network + JSON happen off the main thread; failures
     * (offline, malformed) yield an empty list rather than throwing.
     */
    suspend fun chaptersFor(episode: EpisodeEntity): List<Chapter> = withContext(ioDispatcher) {
        val url = episode.chaptersUrl?.takeIf { it.isNotBlank() } ?: return@withContext emptyList()
        runCatching {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList<Chapter>()
                val body = response.body?.string().orEmpty()
                val array = JSONObject(body).optJSONArray("chapters") ?: return@use emptyList()
                (0 until array.length()).mapNotNull { i ->
                    val o = array.getJSONObject(i)
                    val start = o.optDouble("startTime", -1.0)
                    if (start < 0) return@mapNotNull null
                    Chapter(
                        startMs = (start * 1000).toLong(),
                        title = o.optString("title").ifBlank { "—" },
                        imageUrl = o.optString("img").ifBlank { null },
                    )
                }.sortedBy { it.startMs }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Search results wrapped in a [Result] so callers can tell a thrown error
     * (offline / API failure — retryable) apart from a successful-but-empty
     * response (genuinely no matches). Collapsing both to an empty list made
     * offline look like "no podcasts found".
     */
    suspend fun search(term: String): Result<List<PodcastSearchResult>> = withContext(ioDispatcher) {
        runCatching { searchService.search(term) }
    }

    /** Top shows for a theme (iTunes genre id), to propose on the Discover screen. */
    suspend fun topPodcasts(genreId: Int, limit: Int = 15): List<PodcastSearchResult> =
        withContext(ioDispatcher) {
            runCatching { searchService.topPodcasts(genreId, limit) }.getOrDefault(emptyList())
        }

    // --- playback / state mutations ---

    suspend fun savePosition(episodeId: String, positionMs: Long, durationMs: Long) =
        episodeDao.updatePosition(episodeId, positionMs, durationMs)

    suspend fun setPlayed(episodeId: String, played: Boolean) =
        episodeDao.setPlayed(episodeId, played)

    suspend fun updateDownload(
        episodeId: String,
        state: DownloadState,
        progress: Int,
        path: String?,
    ) = episodeDao.updateDownload(episodeId, state, progress, path)

    // --- Up-Next queue ---

    fun observeQueue(): Flow<List<EpisodeEntity>> = queueDao.observeQueue()
    suspend fun getQueueOnce(): List<EpisodeEntity> = queueDao.getQueueOnce()

    /** Append an episode to the end of the queue (no-op if already queued). */
    suspend fun addToQueueEnd(episodeId: String) = withContext(ioDispatcher) {
        if (queueDao.contains(episodeId)) return@withContext
        val next = (queueDao.maxSortIndex() ?: 0L) + QUEUE_STEP
        queueDao.upsert(QueueItemEntity(episodeId, next))
    }

    /** Splice an episode to the front of the queue so it plays next. */
    suspend fun playNextInQueue(episodeId: String) = withContext(ioDispatcher) {
        val head = (queueDao.minSortIndex() ?: 0L) - QUEUE_STEP
        queueDao.upsert(QueueItemEntity(episodeId, head))
    }

    suspend fun removeFromQueue(episodeId: String) = withContext(ioDispatcher) {
        queueDao.remove(episodeId)
    }

    suspend fun clearQueue() = withContext(ioDispatcher) { queueDao.clear() }

    /** Persist a new explicit ordering of the queue (used by drag/move). */
    suspend fun setQueueOrder(orderedEpisodeIds: List<String>) = withContext(ioDispatcher) {
        queueDao.upsertAll(
            orderedEpisodeIds.mapIndexed { index, id ->
                QueueItemEntity(id, index.toLong() * QUEUE_STEP)
            }
        )
    }

    companion object {
        private const val QUEUE_STEP = 1_000L
    }
}
