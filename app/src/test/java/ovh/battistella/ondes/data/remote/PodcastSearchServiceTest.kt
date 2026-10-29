package ovh.battistella.ondes.data.remote

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class PodcastSearchServiceTest {

    private val service = PodcastSearchService(OkHttpClient())

    private fun result(feed: String, title: String = feed) =
        PodcastSearchResult(feedUrl = feed, title = title, author = "", imageUrl = "")

    @Test
    fun dropsDuplicateFeedsKeepingFirstAndOrder() {
        val input = listOf(
            result("https://feeds.simplecast.com/aPz9Qkq6", "The Room A"),
            result("https://example.com/other"),
            result("https://feeds.simplecast.com/aPz9Qkq6", "The Room B"),
        )

        val out = service.distinctByFeedUrl(input)

        assertEquals(
            listOf("https://feeds.simplecast.com/aPz9Qkq6", "https://example.com/other"),
            out.map { it.feedUrl },
        )
        assertEquals("The Room A", out.first().title)
    }

    @Test
    fun leavesAlreadyUniqueListUntouched() {
        val input = listOf(result("https://a.example/feed"), result("https://b.example/feed"))
        assertEquals(input, service.distinctByFeedUrl(input))
    }
}
