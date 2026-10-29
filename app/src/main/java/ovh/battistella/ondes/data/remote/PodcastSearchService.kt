package ovh.battistella.ondes.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PodcastSearchService @Inject constructor(
    private val client: OkHttpClient,
) {
    private fun country(): String =
        Locale.getDefault().country.lowercase(Locale.ROOT).ifBlank { "us" }

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

    fun topPodcasts(genreId: Int, limit: Int = 15): List<PodcastSearchResult> {
        val ids = chartIds(genreId, limit)
        if (ids.isEmpty()) return emptyList()

        val url = "https://itunes.apple.com/lookup".toHttpUrl().newBuilder()
            .addQueryParameter("id", ids.joinToString(","))
            .addQueryParameter("entity", "podcast")
            .build()
        val results = getJson(url.toString())?.optJSONArray("results") ?: return emptyList()

        val byId = HashMap<String, PodcastSearchResult>(results.length())
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val id = o.optLong("collectionId").takeIf { it != 0L }?.toString() ?: continue
            toResult(o)?.let { byId[id] = it }
        }
        return distinctByFeedUrl(ids.mapNotNull { byId[it] })
    }

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
