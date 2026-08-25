package ovh.battistella.ondes.data.repository

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ovh.battistella.ondes.data.local.DownloadState
import ovh.battistella.ondes.data.local.OndesDatabase
import ovh.battistella.ondes.data.local.episodeId
import ovh.battistella.ondes.data.remote.FeedFetch
import ovh.battistella.ondes.data.remote.RssParser
import ovh.battistella.ondes.testing.MainDispatcherRule
import ovh.battistella.ondes.testing.TestSupport

/**
 * Exercises the feed-refresh reconcile against a real in-memory database:
 * feed-owned content is refreshed for known episodes, while the user's listening
 * progress and download state are never disturbed, and only genuinely new
 * episodes are reported back to the notifier.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PodcastRepositoryTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val rss = mockk<RssParser>()
    private val feedUrl = "https://example.com/feed.xml"
    private lateinit var db: OndesDatabase
    private lateinit var repo: PodcastRepository

    private fun build() {
        db = TestSupport.inMemoryDb()
        repo = TestSupport.repository(db, mainDispatcher.dispatcher, rss = rss)
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun `refresh updates content but preserves progress and downloads for known episodes`() =
        runTest(mainDispatcher.dispatcher) {
            build()

            every { rss.fetch(feedUrl, any(), any()) } returns TestSupport.feedFetch(
                TestSupport.parsedFeed(
                    episodes = listOf(TestSupport.parsedEpisode(guid = "ep-1", title = "Original")),
                ),
            )
            val firstNew = repo.refreshFeed(feedUrl, markSubscribed = true)
            advanceUntilIdle()
            val ep1Id = episodeId(feedUrl, "ep-1")
            assertEquals(listOf(ep1Id), firstNew.map { it.id })

            // The user listens to and downloads ep-1.
            db.episodeDao().updatePosition(ep1Id, 42_000, 60_000, 5_000)
            db.episodeDao().updateDownload(ep1Id, DownloadState.DOWNLOADED, 100, "/data/ep-1.audio")

            // The feed re-publishes ep-1 with an edited title + rotated URL, and a
            // brand-new ep-2.
            every { rss.fetch(feedUrl, any(), any()) } returns TestSupport.feedFetch(
                TestSupport.parsedFeed(
                    episodes = listOf(
                        TestSupport.parsedEpisode(
                            guid = "ep-1",
                            title = "Edited",
                            audioUrl = "https://cdn.example.com/rotated.mp3",
                        ),
                        TestSupport.parsedEpisode(guid = "ep-2", title = "Second"),
                    ),
                ),
            )
            val secondNew = repo.refreshFeed(feedUrl)
            advanceUntilIdle()

            // Only ep-2 is genuinely new.
            assertEquals(listOf(episodeId(feedUrl, "ep-2")), secondNew.map { it.id })

            val ep1 = db.episodeDao().getEpisode(ep1Id)!!
            // Feed-owned content is refreshed...
            assertEquals("Edited", ep1.title)
            assertEquals("https://cdn.example.com/rotated.mp3", ep1.audioUrl)
            // ...but listening progress and the downloaded file are untouched.
            assertEquals(42_000L, ep1.positionMs)
            assertEquals(DownloadState.DOWNLOADED, ep1.downloadState)
            assertEquals("/data/ep-1.audio", ep1.localFilePath)
        }

    /**
     * Two feeds numbering their items "1" both keep their episode. Keyed on the
     * bare GUID, the second feed's episode was silently swallowed by
     * INSERT-IGNORE (issue P0-7).
     */
    @Test
    fun `episodes from different feeds can share a guid`() = runTest(mainDispatcher.dispatcher) {
        build()
        val otherFeed = "https://other.example.com/feed.xml"
        every { rss.fetch(feedUrl, any(), any()) } returns TestSupport.feedFetch(
            TestSupport.parsedFeed(
                title = "First",
                episodes = listOf(TestSupport.parsedEpisode(guid = "1", title = "First show")),
            ),
        )
        every { rss.fetch(otherFeed, any(), any()) } returns TestSupport.feedFetch(
            TestSupport.parsedFeed(
                title = "Second",
                episodes = listOf(TestSupport.parsedEpisode(guid = "1", title = "Second show")),
            ),
        )

        repo.refreshFeed(feedUrl, markSubscribed = true)
        repo.refreshFeed(otherFeed, markSubscribed = true)
        advanceUntilIdle()

        assertEquals("First show", db.episodeDao().getEpisode(episodeId(feedUrl, "1"))?.title)
        assertEquals("Second show", db.episodeDao().getEpisode(episodeId(otherFeed, "1"))?.title)
    }

    /**
     * A feed that answers 304 costs nothing beyond the request: no re-parse, no
     * episode writes, and the stored validators are replayed on the next check
     * (opt. 1).
     */
    @Test
    fun `unchanged feed is not re-parsed and keeps its episodes`() =
        runTest(mainDispatcher.dispatcher) {
            build()
            every { rss.fetch(feedUrl, any(), any()) } returns TestSupport.feedFetch(
                feed = TestSupport.parsedFeed(
                    episodes = listOf(TestSupport.parsedEpisode(guid = "ep-1")),
                ),
                etag = "\"v1\"",
                lastModified = "Wed, 21 Oct 2026 07:28:00 GMT",
            )
            repo.refreshFeed(feedUrl, markSubscribed = true)
            advanceUntilIdle()
            assertEquals("\"v1\"", db.podcastDao().getPodcast(feedUrl)?.etag)

            // Second refresh: the server says nothing changed.
            every {
                rss.fetch(feedUrl, "\"v1\"", "Wed, 21 Oct 2026 07:28:00 GMT")
            } returns FeedFetch.NotModified(etag = "\"v1\"", lastModified = null)

            val fresh = repo.refreshFeed(feedUrl)
            advanceUntilIdle()

            assertEquals(emptyList<String>(), fresh.map { it.id })
            assertEquals("ep-1 survives", "Episode 1", db.episodeDao().getEpisode(episodeId(feedUrl, "ep-1"))?.title)
            // "Last checked" moved on even though nothing was re-parsed.
            assert(db.podcastDao().getPodcast(feedUrl)!!.lastUpdated > 0)
        }

    /**
     * A fresh subscribe pulls in the whole back catalogue; none of it is news.
     */
    @Test
    fun `a first fetch queues nothing for announcement`() = runTest(mainDispatcher.dispatcher) {
        build()
        every { rss.fetch(feedUrl, any(), any()) } returns TestSupport.feedFetch(
            TestSupport.parsedFeed(
                episodes = listOf(
                    TestSupport.parsedEpisode(guid = "ep-1"),
                    TestSupport.parsedEpisode(guid = "ep-2"),
                ),
            ),
        )

        repo.subscribe(feedUrl)
        advanceUntilIdle()

        assertEquals(emptyList<NewEpisodeBatch>(), repo.pendingNewEpisodes())
    }

    /**
     * The bug this whole flag exists for: newness used to be "what this one
     * refresh call inserted", so the foreground refresh that happened to see the
     * episode first consumed the only chance to announce it, and the background
     * worker later found nothing to report.
     */
    @Test
    fun `an episode inserted by a foreground refresh is still announced later`() =
        runTest(mainDispatcher.dispatcher) {
            build()
            every { rss.fetch(feedUrl, any(), any()) } returns TestSupport.feedFetch(
                TestSupport.parsedFeed(
                    episodes = listOf(TestSupport.parsedEpisode(guid = "ep-1")),
                ),
            )
            repo.subscribe(feedUrl)
            advanceUntilIdle()

            // A pull-to-refresh — not the worker — is what picks ep-2 up.
            every { rss.fetch(feedUrl, any(), any()) } returns TestSupport.feedFetch(
                TestSupport.parsedFeed(
                    episodes = listOf(
                        TestSupport.parsedEpisode(guid = "ep-1"),
                        TestSupport.parsedEpisode(guid = "ep-2", title = "Second"),
                    ),
                ),
            )
            repo.refreshFeed(feedUrl)
            advanceUntilIdle()

            // The worker's own refresh finds nothing new to insert, and still
            // owes the user an announcement for ep-2.
            repo.refreshSubscriptions()
            advanceUntilIdle()
            val pending = repo.pendingNewEpisodes()
            assertEquals(1, pending.size)
            assertEquals("Test Show", pending.first().podcastTitle)
            assertEquals(listOf(episodeId(feedUrl, "ep-2")), pending.first().episodes.map { it.id })

            // Announced once, and only once.
            repo.clearPendingNotifications(pending.flatMap { batch -> batch.episodes.map { it.id } })
            advanceUntilIdle()
            assertEquals(emptyList<NewEpisodeBatch>(), repo.pendingNewEpisodes())
        }

    /** Unsubscribing cancels the announcement rather than deferring it. */
    @Test
    fun `unsubscribing drops the feed's unannounced backlog`() =
        runTest(mainDispatcher.dispatcher) {
            build()
            every { rss.fetch(feedUrl, any(), any()) } returns TestSupport.feedFetch(
                TestSupport.parsedFeed(
                    episodes = listOf(TestSupport.parsedEpisode(guid = "ep-1")),
                ),
            )
            repo.subscribe(feedUrl)
            advanceUntilIdle()
            every { rss.fetch(feedUrl, any(), any()) } returns TestSupport.feedFetch(
                TestSupport.parsedFeed(
                    episodes = listOf(
                        TestSupport.parsedEpisode(guid = "ep-1"),
                        TestSupport.parsedEpisode(guid = "ep-2"),
                    ),
                ),
            )
            repo.refreshFeed(feedUrl)
            advanceUntilIdle()
            assertEquals(1, repo.pendingNewEpisodes().size)

            repo.unsubscribe(feedUrl)
            advanceUntilIdle()

            assertEquals(emptyList<NewEpisodeBatch>(), repo.pendingNewEpisodes())
            // Cleared for good, not merely hidden behind the subscribed filter.
            repo.resubscribeAll(listOf(feedUrl))
            advanceUntilIdle()
            assertEquals(emptyList<NewEpisodeBatch>(), repo.pendingNewEpisodes())
        }
}
