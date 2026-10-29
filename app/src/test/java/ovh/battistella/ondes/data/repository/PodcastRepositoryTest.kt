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

            db.episodeDao().updatePosition(ep1Id, 42_000, 60_000, 5_000)
            db.episodeDao().updateDownload(ep1Id, DownloadState.DOWNLOADED, 100, "/data/ep-1.audio")

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

            assertEquals(listOf(episodeId(feedUrl, "ep-2")), secondNew.map { it.id })

            val ep1 = db.episodeDao().getEpisode(ep1Id)!!
            assertEquals("Edited", ep1.title)
            assertEquals("https://cdn.example.com/rotated.mp3", ep1.audioUrl)
            assertEquals(42_000L, ep1.positionMs)
            assertEquals(DownloadState.DOWNLOADED, ep1.downloadState)
            assertEquals("/data/ep-1.audio", ep1.localFilePath)
        }

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

            every {
                rss.fetch(feedUrl, "\"v1\"", "Wed, 21 Oct 2026 07:28:00 GMT")
            } returns FeedFetch.NotModified(etag = "\"v1\"", lastModified = null)

            val fresh = repo.refreshFeed(feedUrl)
            advanceUntilIdle()

            assertEquals(emptyList<String>(), fresh.map { it.id })
            assertEquals("ep-1 survives", "Episode 1", db.episodeDao().getEpisode(episodeId(feedUrl, "ep-1"))?.title)
            assert(db.podcastDao().getPodcast(feedUrl)!!.lastUpdated > 0)
        }

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

            repo.refreshSubscriptions()
            advanceUntilIdle()
            val pending = repo.pendingNewEpisodes()
            assertEquals(1, pending.size)
            assertEquals("Test Show", pending.first().podcastTitle)
            assertEquals(listOf(episodeId(feedUrl, "ep-2")), pending.first().episodes.map { it.id })

            repo.clearPendingNotifications(pending.flatMap { batch -> batch.episodes.map { it.id } })
            advanceUntilIdle()
            assertEquals(emptyList<NewEpisodeBatch>(), repo.pendingNewEpisodes())
        }

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
            repo.resubscribeAll(listOf(feedUrl))
            advanceUntilIdle()
            assertEquals(emptyList<NewEpisodeBatch>(), repo.pendingNewEpisodes())
        }

    @Test
    fun `refreshing a never-seen feed previews it without subscribing`() =
        runTest(mainDispatcher.dispatcher) {
            build()
            every { rss.fetch(feedUrl, any(), any()) } returns TestSupport.feedFetch(
                TestSupport.parsedFeed(
                    episodes = listOf(TestSupport.parsedEpisode(guid = "ep-1")),
                ),
            )

            repo.refreshFeed(feedUrl)
            advanceUntilIdle()

            assertEquals(false, repo.getPodcastOnce(feedUrl)?.subscribed)
            assertEquals(emptySet<String>(), repo.getSubscribedFeedUrlsOnce())

            repo.subscribe(feedUrl)
            advanceUntilIdle()
            assertEquals(setOf(feedUrl), repo.getSubscribedFeedUrlsOnce())
            assertEquals(1, repo.getEpisodesOnce(feedUrl).size)
        }
}
