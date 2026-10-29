package ovh.battistella.ondes.data.remote

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
class RssParserTest {

    private val parser = RssParser(OkHttpClient())

    private val feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
          <channel>
            <title>Sample Cast</title>
            <itunes:author>Jane Doe</itunes:author>
            <description>A sample feed.</description>
            <link>https://site.example</link>
            <itunes:image href="https://img.example/cover.jpg"/>
            <item>
              <title>First Episode</title>
              <guid>guid-1</guid>
              <enclosure url="https://cdn.example/1.mp3" type="audio/mpeg"/>
              <itunes:duration>1:00:00</itunes:duration>
              <pubDate>Tue, 10 Jun 2025 10:00:00 +0000</pubDate>
            </item>
            <item>
              <title>Malicious Local File</title>
              <guid>guid-2</guid>
              <enclosure url="file:///etc/passwd" type="audio/mpeg"/>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    private fun parse(xml: String) = parser.parse(ByteArrayInputStream(xml.toByteArray()))

    @Test
    fun `parses channel metadata`() {
        val result = parse(feed)
        assertEquals("Sample Cast", result.title)
        assertEquals("Jane Doe", result.author)
        assertEquals("A sample feed.", result.description)
        assertEquals("https://img.example/cover.jpg", result.imageUrl)
        assertEquals("https://site.example", result.link)
    }

    @Test
    fun `drops non-http enclosures and keeps playable ones`() {
        val episodes = parse(feed).episodes
        assertEquals(listOf("guid-1"), episodes.map { it.guid })
        assertEquals("https://cdn.example/1.mp3", episodes.first().audioUrl)
    }

    @Test
    fun `parses HH-MM-SS duration into milliseconds`() {
        assertEquals(3_600_000L, parse(feed).episodes.first().durationMs)
    }

    @Test
    fun `falls back to defaults for an empty document`() {
        val result = parse("<rss><channel></channel></rss>")
        assertEquals("Podcast", result.title)
        assertEquals(emptyList<String>(), result.episodes.map { it.guid })
    }

    @Test
    fun `mixed content in a description does not abort the whole feed`() {
        val mixed = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss>
              <channel>
                <title>Mixed Cast</title>
                <item>
                  <title>Has Markup</title>
                  <guid>guid-mixed</guid>
                  <description>Intro <b>bold</b> outro</description>
                  <enclosure url="https://cdn.example/mixed.mp3" type="audio/mpeg"/>
                </item>
                <item>
                  <title>Plain</title>
                  <guid>guid-plain</guid>
                  <enclosure url="https://cdn.example/plain.mp3" type="audio/mpeg"/>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val episodes = parse(mixed).episodes

        assertEquals(listOf("guid-mixed", "guid-plain"), episodes.map { it.guid })
    }
}
