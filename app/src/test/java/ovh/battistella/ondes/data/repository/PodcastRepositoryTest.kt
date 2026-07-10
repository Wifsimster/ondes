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

            every { rss.fetchAndParse(feedUrl) } returns TestSupport.parsedFeed(
                episodes = listOf(TestSupport.parsedEpisode(guid = "ep-1", title = "Original")),
            )
            val firstNew = repo.refreshFeed(feedUrl, markSubscribed = true)
            advanceUntilIdle()
            assertEquals(listOf("ep-1"), firstNew.map { it.id })

            // The user listens to and downloads ep-1.
            db.episodeDao().updatePosition("ep-1", 42_000, 60_000)
            db.episodeDao().updateDownload("ep-1", DownloadState.DOWNLOADED, 100, "/data/ep-1.audio")

            // The feed re-publishes ep-1 with an edited title + rotated URL, and a
            // brand-new ep-2.
            every { rss.fetchAndParse(feedUrl) } returns TestSupport.parsedFeed(
                episodes = listOf(
                    TestSupport.parsedEpisode(
                        guid = "ep-1",
                        title = "Edited",
                        audioUrl = "https://cdn.example.com/rotated.mp3",
                    ),
                    TestSupport.parsedEpisode(guid = "ep-2", title = "Second"),
                ),
            )
            val secondNew = repo.refreshFeed(feedUrl)
            advanceUntilIdle()

            // Only ep-2 is genuinely new.
            assertEquals(listOf("ep-2"), secondNew.map { it.id })

            val ep1 = db.episodeDao().getEpisode("ep-1")!!
            // Feed-owned content is refreshed...
            assertEquals("Edited", ep1.title)
            assertEquals("https://cdn.example.com/rotated.mp3", ep1.audioUrl)
            // ...but listening progress and the downloaded file are untouched.
            assertEquals(42_000L, ep1.positionMs)
            assertEquals(DownloadState.DOWNLOADED, ep1.downloadState)
            assertEquals("/data/ep-1.audio", ep1.localFilePath)
        }
}
