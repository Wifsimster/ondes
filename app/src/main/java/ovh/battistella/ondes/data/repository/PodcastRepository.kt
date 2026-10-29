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

    fun observeEpisodesPaged(feedUrl: String, limit: Int): Flow<List<EpisodeEntity>> =
        episodeDao.observeForFeedPaged(feedUrl, limit)

    fun observeEpisodeCount(feedUrl: String): Flow<Int> = episodeDao.observeCountForFeed(feedUrl)
    fun observeEpisode(id: String): Flow<EpisodeEntity?> = episodeDao.observeEpisode(id)
    fun observeLatest(): Flow<List<EpisodeEntity>> = episodeDao.observeLatest()
    fun observeInProgress(): Flow<List<EpisodeEntity>> = episodeDao.observeInProgress()
    fun observeDownloaded(): Flow<List<EpisodeEntity>> = episodeDao.observeDownloaded()

    suspend fun getEpisode(id: String): EpisodeEntity? = episodeDao.getEpisode(id)

    suspend fun getPendingDownloadIds(): List<String> = episodeDao.getPendingDownloadIds()
    suspend fun getPodcastOnce(feedUrl: String): PodcastEntity? = podcastDao.getPodcast(feedUrl)

    suspend fun setPodcastSpeed(feedUrl: String, speed: Float?) =
        podcastDao.setOverrideSpeed(feedUrl, speed)

    suspend fun setPodcastAutoDownload(feedUrl: String, enabled: Boolean) =
        podcastDao.setAutoDownload(feedUrl, enabled)

    suspend fun getSubscribedFeedUrlsOnce(): Set<String> =
        podcastDao.getAll().asSequence().filter { it.subscribed }.map { it.feedUrl }.toSet()

    suspend fun getSubscriptionsOnce(): List<PodcastEntity> = observeSubscriptions().first()
    suspend fun getEpisodesOnce(feedUrl: String): List<EpisodeEntity> = observeEpisodes(feedUrl).first()
    suspend fun getInProgressOnce(): List<EpisodeEntity> = observeInProgress().first()
    suspend fun getDownloadedOnce(): List<EpisodeEntity> = observeDownloaded().first()
    suspend fun getLatestOnce(): List<EpisodeEntity> = observeLatest().first()

    suspend fun subscribe(feedUrl: String): Result<String> = withContext(ioDispatcher) {
        runCatching {
            refreshFeed(feedUrl, markSubscribed = true)
            feedUrl
        }
    }

    suspend fun subscribeAll(feedUrls: List<String>): List<String> = withContext(ioDispatcher) {
        inParallel(feedUrls) { url -> url.takeIf { subscribe(it).isSuccess } }.filterNotNull()
    }

    suspend fun unsubscribe(feedUrl: String) = withContext(ioDispatcher) {
        podcastDao.setSubscribed(feedUrl, false)
        episodeDao.clearPendingNotificationForFeed(feedUrl)
    }

    suspend fun unsubscribeAll(feedUrls: Collection<String>) = withContext(ioDispatcher) {
        feedUrls.forEach {
            podcastDao.setSubscribed(it, false)
            episodeDao.clearPendingNotificationForFeed(it)
        }
    }

    suspend fun resubscribeAll(feedUrls: Collection<String>) = withContext(ioDispatcher) {
        feedUrls.forEach { podcastDao.setSubscribed(it, true) }
    }

    suspend fun refreshFeed(feedUrl: String, markSubscribed: Boolean = false): List<EpisodeEntity> =
        withContext(ioDispatcher) {
            val existing = podcastDao.getPodcast(feedUrl)
            val firstFetch = existing == null || existing.lastUpdated == 0L
            val conditional = existing != null && !markSubscribed
            val fetched = rssParser.fetch(
                feedUrl = feedUrl,
                etag = existing?.etag?.takeIf { conditional },
                lastModified = existing?.lastModified?.takeIf { conditional },
            )
            val updated = when (fetched) {
                is FeedFetch.NotModified -> {
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
                subscribed = markSubscribed || (existing?.subscribed ?: false),
                lastUpdated = System.currentTimeMillis(),
                overrideSpeed = existing?.overrideSpeed,
                autoDownload = existing?.autoDownload ?: false,
                etag = updated.etag ?: existing?.etag,
                lastModified = updated.lastModified ?: existing?.lastModified,
            )
            val fallbackImage = parsed.imageUrl
            val rows = parsed.episodes.map { e ->
                EpisodeEntity(
                    id = episodeId(feedUrl, e.guid),
                    feedUrl = feedUrl,
                    title = e.title,
                    description = e.description,
                    audioUrl = e.audioUrl,
                    imageUrl = e.imageUrl.ifEmpty { fallbackImage },
                    pubDate = e.pubDate,
                    durationMs = e.durationMs,
                    chaptersUrl = e.chaptersUrl.ifEmpty { null },
                    pendingNotification = !firstFetch,
                )
            }
            db.withTransaction {
                podcastDao.upsert(podcast)
                val rowIds = episodeDao.insertNew(rows)
                rows.forEachIndexed { index, row ->
                    val isNew = rowIds.getOrElse(index) { -1L } != -1L
                    if (!isNew) {
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
                    row.chaptersUrl?.let { episodeDao.updateChaptersUrl(row.id, it) }
                }
                rows.filterIndexed { index, _ -> rowIds.getOrElse(index) { -1L } != -1L }
            }
        }

    suspend fun refreshAllSubscriptions(feedUrls: List<String>) = withContext(ioDispatcher) {
        inParallel(feedUrls) { url -> runCatching { refreshFeed(url) } }
        Unit
    }

    private suspend fun <T, R> inParallel(items: List<T>, block: suspend (T) -> R): List<R> =
        coroutineScope {
            val gate = Semaphore(REFRESH_CONCURRENCY)
            items.map { item -> async { gate.withPermit { block(item) } } }.awaitAll()
        }

    suspend fun refreshSubscriptions() =
        refreshAllSubscriptions(getSubscriptionsOnce().map { it.feedUrl })

    suspend fun pendingNewEpisodes(): List<NewEpisodeBatch> = withContext(ioDispatcher) {
        episodeDao.getPendingNotification()
            .groupBy { it.feedUrl }
            .mapNotNull { (feedUrl, episodes) ->
                val title = podcastDao.getPodcast(feedUrl)?.title ?: return@mapNotNull null
                NewEpisodeBatch(feedUrl, title, episodes)
            }
    }

    suspend fun clearPendingNotifications(episodeIds: List<String>) = withContext(ioDispatcher) {
        episodeIds.chunked(CLEAR_CHUNK).forEach { episodeDao.clearPendingNotification(it) }
    }

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

    suspend fun search(term: String): Result<List<PodcastSearchResult>> = withContext(ioDispatcher) {
        runCatching { searchService.search(term) }
    }

    suspend fun topPodcasts(genreId: Int, limit: Int = 15): List<PodcastSearchResult> =
        withContext(ioDispatcher) {
            runCatching { searchService.topPodcasts(genreId, limit) }.getOrDefault(emptyList())
        }

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

    fun observeQueue(): Flow<List<EpisodeEntity>> = queueDao.observeQueue()
    suspend fun getQueueOnce(): List<EpisodeEntity> = queueDao.getQueueOnce()

    suspend fun addToQueueEnd(episodeId: String) = withContext(ioDispatcher) {
        db.withTransaction {
            if (queueDao.contains(episodeId)) return@withTransaction
            val next = (queueDao.maxSortIndex() ?: 0L) + QUEUE_STEP
            queueDao.upsert(QueueItemEntity(episodeId, next))
        }
    }

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

    suspend fun setQueueOrder(orderedEpisodeIds: List<String>) = withContext(ioDispatcher) {
        queueDao.upsertAll(
            orderedEpisodeIds.mapIndexed { index, id ->
                QueueItemEntity(id, index.toLong() * QUEUE_STEP)
            }
        )
    }

    companion object {
        private const val QUEUE_STEP = 1_000L

        private const val REFRESH_CONCURRENCY = 5

        private const val CLEAR_CHUNK = 500
    }
}
