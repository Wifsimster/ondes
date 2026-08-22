package ovh.battistella.ondes.testing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import ovh.battistella.ondes.data.local.DownloadState
import ovh.battistella.ondes.data.local.EpisodeEntity
import ovh.battistella.ondes.data.local.OndesDatabase
import ovh.battistella.ondes.data.local.PodcastEntity
import ovh.battistella.ondes.data.remote.FeedFetch
import ovh.battistella.ondes.data.remote.ParsedEpisode
import ovh.battistella.ondes.data.remote.ParsedFeed
import ovh.battistella.ondes.data.remote.PodcastSearchResult
import ovh.battistella.ondes.data.remote.PodcastSearchService
import ovh.battistella.ondes.data.remote.RssParser
import ovh.battistella.ondes.data.repository.PodcastRepository
import ovh.battistella.ondes.playback.PlaybackConnection
import ovh.battistella.ondes.playback.PlayerUiState
import java.util.concurrent.Executor

/**
 * Shared test scaffolding: a real in-memory Room database wired into a real
 * [PodcastRepository] (so DAO queries are exercised for real) with a mocked
 * network layer, plus entity/feed fixtures and helpers for the service-bound
 * collaborators we never want to actually start in a host-JVM test.
 */
object TestSupport {

    /** A synchronous executor so Room runs queries on the calling thread — keeps
     *  `runTest` deterministic with no real background threads. */
    private val directExecutor = Executor { it.run() }

    fun inMemoryDb(): OndesDatabase {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(ctx, OndesDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()
    }

    /** A real repository over [db], with [rss] and [search] mocked (no network). */
    fun repository(
        db: OndesDatabase,
        io: CoroutineDispatcher,
        rss: RssParser = mockk(relaxed = true),
        search: PodcastSearchService = mockk(relaxed = true),
    ): PodcastRepository = PodcastRepository(
        db = db,
        podcastDao = db.podcastDao(),
        episodeDao = db.episodeDao(),
        queueDao = db.queueDao(),
        rssParser = rss,
        searchService = search,
        httpClient = OkHttpClient(),
        ioDispatcher = io,
    )

    /**
     * A relaxed [PlaybackConnection] mock with a settable [PlayerUiState] flow.
     * The real connection binds a MediaController to the playback service, which
     * can't run on a host JVM, so it is always faked.
     */
    fun mockConnection(
        stateFlow: MutableStateFlow<PlayerUiState> = MutableStateFlow(PlayerUiState()),
    ): PlaybackConnection = mockk(relaxed = true) {
        every { state } returns stateFlow
    }

    // --- fixtures ---

    fun parsedFeed(
        title: String = "Test Show",
        episodes: List<ParsedEpisode> = listOf(parsedEpisode()),
        imageUrl: String = "https://img.example.com/show.png",
    ) = ParsedFeed(
        title = title,
        author = "Test Author",
        description = "A test podcast.",
        imageUrl = imageUrl,
        link = "https://example.com/show",
        episodes = episodes,
    )

    /** A fetched-and-changed feed, as [RssParser.fetch] returns it. */
    fun feedFetch(
        feed: ParsedFeed = parsedFeed(),
        etag: String? = null,
        lastModified: String? = null,
    ) = FeedFetch.Updated(feed = feed, etag = etag, lastModified = lastModified)

    fun parsedEpisode(
        guid: String = "ep-1",
        title: String = "Episode 1",
        audioUrl: String = "https://cdn.example.com/ep1.mp3",
        pubDate: Long = 1_000L,
        durationMs: Long = 60_000L,
    ) = ParsedEpisode(
        guid = guid,
        title = title,
        description = "desc",
        audioUrl = audioUrl,
        imageUrl = "",
        pubDate = pubDate,
        durationMs = durationMs,
    )

    fun podcast(
        feedUrl: String = "https://example.com/feed.xml",
        title: String = "Test Show",
        subscribed: Boolean = true,
        lastUpdated: Long = 1_000L,
    ) = PodcastEntity(
        feedUrl = feedUrl,
        title = title,
        author = "Test Author",
        description = "A test podcast.",
        imageUrl = "https://img.example.com/show.png",
        link = "https://example.com/show",
        subscribed = subscribed,
        lastUpdated = lastUpdated,
    )

    fun episode(
        id: String = "ep-1",
        feedUrl: String = "https://example.com/feed.xml",
        title: String = "Episode 1",
        pubDate: Long = 1_000L,
        positionMs: Long = 0L,
        isFinished: Boolean = false,
        downloadState: DownloadState = DownloadState.NONE,
        localFilePath: String? = null,
    ) = EpisodeEntity(
        id = id,
        feedUrl = feedUrl,
        title = title,
        description = "desc",
        audioUrl = "https://cdn.example.com/$id.mp3",
        imageUrl = "https://img.example.com/$id.png",
        pubDate = pubDate,
        durationMs = 60_000L,
        positionMs = positionMs,
        isFinished = isFinished,
        downloadState = downloadState,
        localFilePath = localFilePath,
    )

    fun searchResult(
        feedUrl: String = "https://example.com/feed.xml",
        title: String = "Test Show",
    ) = PodcastSearchResult(
        feedUrl = feedUrl,
        title = title,
        author = "Test Author",
        imageUrl = "https://img.example.com/show.png",
    )
}
