package ovh.battistella.ondes.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Podcast discovery via the public iTunes APIs. No key required, returns a
 * usable RSS [feedUrl] for each match so we can subscribe directly.
 */
@Singleton
class PodcastSearchService @Inject constructor(
    private val client: OkHttpClient,
) {
    /**
     * iTunes storefront to query, derived from the device locale's region (ISO
     * 3166-1 alpha-2, lowercased) so Search/Discover are localized, falling back
     * to "us" when the device reports no region. Read per call rather than cached
     * at construction so a region change mid-session is honoured (P2).
     */
    private fun country(): String =
        Locale.getDefault().country.lowercase(Locale.ROOT).ifBlank { "us" }

    /** Free-text search by show name / author. */
    fun search(term: String): List<PodcastSearchResult> {
        if (term.isBlank()) return emptyList()
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("media", "podcast")
            .addQueryParameter("entity", "podcast")
            .addQueryParameter("country", country())
            .addQueryParameter("limit", "30")
            .addQueryParameter("term", term)
            .build()
        val results = getJson(url.toString())?.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<PodcastSearchResult>(results.length())
        for (i in 0 until results.length()) {
            results.optJSONObject(i)?.let { o -> toResult(o)?.let(out::add) }
        }
        return distinctByFeedUrl(out)
    }

    /**
     * The current top podcasts for an iTunes [genreId], so Discover can propose
     * a handful of the best shows per theme. Reads the genre chart for the most
     * popular collection ids, then resolves each to a subscribable feed URL.
     */
    fun topPodcasts(genreId: Int, limit: Int = 15): List<PodcastSearchResult> {
        val ids = chartIds(genreId, limit)
        if (ids.isEmpty()) return emptyList()

        val url = "https://itunes.apple.com/lookup".toHttpUrl().newBuilder()
            .addQueryParameter("id", ids.joinToString(","))
            .addQueryParameter("entity", "podcast")
            .build()
        val results = getJson(url.toString())?.optJSONArray("results") ?: return emptyList()

        // Index by collection id so we can preserve the chart's ranking order
        // (lookup doesn't guarantee it) and drop entries with no usable feed.
        val byId = HashMap<String, PodcastSearchResult>(results.length())
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val id = o.optLong("collectionId").takeIf { it != 0L }?.toString() ?: continue
            toResult(o)?.let { byId[id] = it }
        }
        return distinctByFeedUrl(ids.mapNotNull { byId[it] })
    }

    /** Top collection ids for a genre, ranked, from the iTunes "top podcasts" chart. */
    private fun chartIds(genreId: Int, limit: Int): List<String> {
        val url = "https://itunes.apple.com/${country()}/rss/toppodcasts/limit=$limit/genre=$genreId/json"
        val feed = getJson(url)?.optJSONObject("feed") ?: return emptyList()
        val entries = feed.optJSONArray("entry") ?: return emptyList()
        val ids = ArrayList<String>(entries.length())
        for (i in 0 until entries.length()) {
            val id = entries.optJSONObject(i)
                ?.optJSONObject("id")
                ?.optJSONObject("attributes")
                ?.optString("im:id")
            if (!id.isNullOrBlank()) ids += id
        }
        return ids
    }

    private fun getJson(url: String): JSONObject? {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            return JSONObject(body)
        }
    }

    /**
     * Keep the first result per feedUrl, preserving order. iTunes can return the
     * same show twice (a feed published under several collection ids); the result
     * lists are keyed by feedUrl in Compose, and a duplicate key crashes the
     * LazyColumn ("Key was already used"). Reproduces e.g. searching "the room"
     * on the French storefront.
     */
    internal fun distinctByFeedUrl(results: List<PodcastSearchResult>): List<PodcastSearchResult> =
        results.distinctBy { it.feedUrl }

    private fun toResult(o: JSONObject): PodcastSearchResult? {
        val feed = o.optString("feedUrl")
        if (feed.isNullOrBlank()) return null
        return PodcastSearchResult(
            feedUrl = feed,
            title = o.optString("collectionName"),
            author = o.optString("artistName"),
            imageUrl = o.optString("artworkUrl600").ifEmpty { o.optString("artworkUrl100") },
        )
    }
}
