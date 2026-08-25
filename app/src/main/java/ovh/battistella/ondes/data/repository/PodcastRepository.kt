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
import ovh.battistella.ondes.data.local.episodeId
import ovh.battistella.ondes.data.local.QueueItemEntity
import ovh.battistella.ondes.data.remote.FeedFetch
import ovh.battistella.ondes.data.remote.PodcastSearchResult
import ovh.battistella.ondes.data.remote.PodcastSearchService
import ovh.battistella.ondes.data.remote.RssParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A subscription plus its episodes that are still owed a "new episode"
 * notification, so callers can post one per podcast (named, grouped).
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

    /** The newest [limit] episodes of a feed, for the paged podcast screen (opt. 7). */
    fun observeEpisodesPaged(feedUrl: String, limit: Int): Flow<List<EpisodeEntity>> =
        episodeDao.observeForFeedPaged(feedUrl, limit)

    fun observeEpisodeCount(feedUrl: String): Flow<Int> = episodeDao.observeCountForFeed(feedUrl)
    fun observeEpisode(id: String): Flow<EpisodeEntity?> = episodeDao.observeEpisode(id)
    fun observeLatest(): Flow<List<EpisodeEntity>> = episodeDao.observeLatest()
    fun observeInProgress(): Flow<List<EpisodeEntity>> = episodeDao.observeInProgress()
    fun observeDownloaded(): Flow<List<EpisodeEntity>> = episodeDao.observeDownloaded()

    suspend fun getEpisode(id: String): EpisodeEntity? = episodeDao.getEpisode(id)

    /** Episodes with a download still queued or running (their partial files are live). */
    suspend fun getPendingDownloadIds(): List<String> = episodeDao.getPendingDownloadIds()
    suspend fun getPodcastOnce(feedUrl: String): PodcastEntity? = podcastDao.getPodcast(feedUrl)

    /** Per-podcast playback-speed override (null clears it, falling back to global). */
    suspend fun setPodcastSpeed(feedUrl: String, speed: Float?) =
        podcastDao.setOverrideSpeed(feedUrl, speed)

    suspend fun setPodcastAutoDownload(feedUrl: String, enabled: Boolean) =
        podcastDao.setAutoDownload(feedUrl, enabled)

    /**
     * One-shot set of subscribed feed URLs via a plain query (no live table
     * observer), for seeding initial UI state without a Flow subscription that
     * could re-enter a concurrent refresh transaction.
     */
    suspend fun getSubscribedFeedUrlsOnce(): Set<String> =
        podcastDao.getAll().asSequence().filter { it.subscribed }.map { it.feedUrl }.toSet()

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

    /**
     * Subscribe to many feeds at once with bounded concurrency, returning the
     * URLs that were actually added. Used by OPML import and onboarding, which
     * otherwise fetched dozens of feeds strictly one after another (opt. 2).
     */
    suspend fun subscribeAll(feedUrls: List<String>): List<String> = withContext(ioDispatcher) {
        inParallel(feedUrls) { url -> url.takeIf { subscribe(it).isSuccess } }.filterNotNull()
    }

    suspend fun unsubscribe(feedUrl: String) = withContext(ioDispatcher) {
        podcastDao.setSubscribed(feedUrl, false)
        // Drop anything this feed had queued for announcement, so re-subscribing
        // later doesn't open with a notification about episodes from before.
        episodeDao.clearPendingNotificationForFeed(feedUrl)
    }

    /** Soft-unsubscribe several feeds at once (library multi-select). */
    suspend fun unsubscribeAll(feedUrls: Collection<String>) = withContext(ioDispatcher) {
        feedUrls.forEach {
            podcastDao.setSubscribed(it, false)
            episodeDao.clearPendingNotificationForFeed(it)
        }
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
            val existing = podcastDao.getPodcast(feedUrl)
            // A feed nobody has fetched yet arrives as one big back catalogue:
            // flagging it would announce hundreds of "new" episodes on a fresh
            // subscribe. Everything after that first fetch is genuinely new.
            val firstFetch = existing == null || existing.lastUpdated == 0L
            // Replay the validators from the last fetch: an unchanged feed then
            // costs one conditional request instead of a full download + parse
            // (opt. 1). A first fetch, or one we must not short-circuit (a fresh
            // subscribe), sends no validators.
            val conditional = existing != null && !markSubscribed
            val fetched = rssParser.fetch(
                feedUrl = feedUrl,
                etag = existing?.etag?.takeIf { conditional },
                lastModified = existing?.lastModified?.takeIf { conditional },
            )
            val updated = when (fetched) {
                is FeedFetch.NotModified -> {
                    // Nothing changed: just record that we looked, so the next
                    // refresh cycle doesn't treat this feed as never-fetched.
                    podcastDao.markChecked(
                        feedUrl = feedUrl,
                        checkedAt = System.currentTimeMillis(),
                        etag = fetched.etag,
                        lastModified = fetched.lastModified,
                    )
                    return@withContext emptyList()
                }
                is FeedFetch.Updated -> fetched
            }
            val parsed = updated.feed
            val podcast = PodcastEntity(
                feedUrl = feedUrl,
                title = parsed.title,
                author = parsed.author,
                description = parsed.description,
                imageUrl = parsed.imageUrl.ifEmpty { existing?.imageUrl.orEmpty() },
                link = parsed.link,
                subscribed = markSubscribed || (existing?.subscribed ?: true),
                lastUpdated = System.currentTimeMillis(),
                // The upsert replaces the whole row, so the user's own settings
                // for this podcast have to be carried across explicitly.
                overrideSpeed = existing?.overrideSpeed,
                autoDownload = existing?.autoDownload ?: false,
                etag = updated.etag ?: existing?.etag,
                lastModified = updated.lastModified ?: existing?.lastModified,
            )
            val fallbackImage = parsed.imageUrl
            val rows = parsed.episodes.map { e ->
                EpisodeEntity(
                    // Feed-scoped: a GUID is only unique within its own feed
                    // (issue P0-7).
                    id = episodeId(feedUrl, e.guid),
                    feedUrl = feedUrl,
                    title = e.title,
                    description = e.description,
                    audioUrl = e.audioUrl,
                    imageUrl = e.imageUrl.ifEmpty { fallbackImage },
                    pubDate = e.pubDate,
                    durationMs = e.durationMs,
                    chaptersUrl = e.chaptersUrl.ifEmpty { null },
                    // Only reaches the database for rows INSERT-IGNORE actually
                    // inserts; a known episode keeps whatever flag it already has.
                    pendingNotification = !firstFetch,
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
        inParallel(feedUrls) { url -> runCatching { refreshFeed(url) } }
        Unit
    }

    /**
     * Run [block] over [items] with at most [REFRESH_CONCURRENCY] in flight.
     *
     * Feed fan-out used to be strictly serial, so a pull-to-refresh over 50
     * subscriptions took as long as 50 round-trips back to back (opt. 2). The
     * work is network-bound; the permit keeps it from opening a socket per
     * subscription at once.
     */
    private suspend fun <T, R> inParallel(items: List<T>, block: suspend (T) -> R): List<R> =
        coroutineScope {
            val gate = Semaphore(REFRESH_CONCURRENCY)
            items.map { item -> async { gate.withPermit { block(item) } } }.awaitAll()
        }

    /**
     * Refresh every subscribed feed, swallowing per-feed failures so one dead
     * feed doesn't abandon the rest of the cycle.
     */
    suspend fun refreshSubscriptions() =
        refreshAllSubscriptions(getSubscriptionsOnce().map { it.feedUrl })

    /**
     * Everything that has arrived since the last announcement, grouped per
     * podcast so each gets its own notification.
     *
     * Read from the episodes themselves rather than from the return value of one
     * refresh call: whichever path fetched the feed — the background worker, a
     * pull-to-refresh, the podcast screen — the episode is still owed an
     * announcement until [clearPendingNotifications] says otherwise.
     */
    suspend fun pendingNewEpisodes(): List<NewEpisodeBatch> = withContext(ioDispatcher) {
        episodeDao.getPendingNotification()
            .groupBy { it.feedUrl }
            .mapNotNull { (feedUrl, episodes) ->
                val title = podcastDao.getPodcast(feedUrl)?.title ?: return@mapNotNull null
                NewEpisodeBatch(feedUrl, title, episodes)
            }
    }

    /** Mark episodes as announced so the next refresh doesn't repeat them. */
    suspend fun clearPendingNotifications(episodeIds: List<String>) = withContext(ioDispatcher) {
        // SQLite binds a bounded number of arguments per statement; a first
        // refresh after a long absence can easily exceed it.
        episodeIds.chunked(CLEAR_CHUNK).forEach { episodeDao.clearPendingNotification(it) }
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
        episodeDao.updatePosition(episodeId, positionMs, durationMs, System.currentTimeMillis())

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

    /**
     * Append an episode to the end of the queue (no-op if already queued).
     * Read-then-write inside one transaction: two concurrent adds could
     * otherwise both read the same MAX(sortIndex) and land on the same position
     * (issue P2).
     */
    suspend fun addToQueueEnd(episodeId: String) = withContext(ioDispatcher) {
        db.withTransaction {
            if (queueDao.contains(episodeId)) return@withTransaction
            val next = (queueDao.maxSortIndex() ?: 0L) + QUEUE_STEP
            queueDao.upsert(QueueItemEntity(episodeId, next))
        }
    }

    /** Splice an episode to the front of the queue so it plays next. */
    suspend fun playNextInQueue(episodeId: String) = withContext(ioDispatcher) {
        db.withTransaction {
            val head = (queueDao.minSortIndex() ?: 0L) - QUEUE_STEP
            queueDao.upsert(QueueItemEntity(episodeId, head))
        }
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

        /** How many feeds may be fetched at once during a fan-out refresh. */
        private const val REFRESH_CONCURRENCY = 5

        /** Episode ids cleared per UPDATE, kept under SQLite's bind-argument cap. */
        private const val CLEAR_CHUNK = 500
    }
}
