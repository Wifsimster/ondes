package ovh.battistella.ondes.data.opml

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ovh.battistella.ondes.data.local.OndesDatabase
import ovh.battistella.ondes.data.remote.RssParser
import ovh.battistella.ondes.data.repository.PodcastRepository
import ovh.battistella.ondes.testing.TestSupport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
class OpmlManagerTest {

    private val rss = mockk<RssParser>(relaxed = true)
    private lateinit var db: OndesDatabase
    private lateinit var repo: PodcastRepository
    private lateinit var opml: OpmlManager

    private fun build() {
        db = TestSupport.inMemoryDb()
        repo = TestSupport.repository(db, Dispatchers.Unconfined, rss = rss)
        opml = OpmlManager(repo)
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun `import subscribes http feeds and skips non-http outlines`() = runBlocking {
        build()
        every { rss.fetch(any(), any(), any()) } answers {
            TestSupport.feedFetch(TestSupport.parsedFeed(title = "Imported"))
        }
        val doc = """
            <opml version="2.0"><body>
              <outline type="rss" text="A" xmlUrl="https://a.example/feed"/>
              <outline type="rss" title="B" xmlUrl="https://b.example/feed"/>
              <outline type="rss" text="Evil" xmlUrl="file:///etc/passwd"/>
            </body></opml>
        """.trimIndent()

        val added = opml.import(ByteArrayInputStream(doc.toByteArray()))

        assertEquals(2, added)
        assertEquals("Imported", db.podcastDao().getPodcast("https://a.example/feed")?.title)
        assertEquals("Imported", db.podcastDao().getPodcast("https://b.example/feed")?.title)
        assertNull(db.podcastDao().getPodcast("file:///etc/passwd"))
    }

    @Test
    fun `export writes every subscription as an outline`() = runBlocking {
        build()
        db.podcastDao().upsert(TestSupport.podcast(feedUrl = "https://a.example/feed", title = "Show A"))
        db.podcastDao().upsert(TestSupport.podcast(feedUrl = "https://b.example/feed", title = "Show B"))

        val out = ByteArrayOutputStream()
        val count = opml.export(out)
        val xml = out.toString("UTF-8")

        assertEquals(2, count)
        assertTrue(xml.contains("https://a.example/feed"))
        assertTrue(xml.contains("https://b.example/feed"))
        assertTrue(xml.contains("Show A"))
    }
}
