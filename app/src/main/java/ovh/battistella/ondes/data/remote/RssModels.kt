package ovh.battistella.ondes.data.remote

sealed interface FeedFetch {
    data class Updated(
        val feed: ParsedFeed,
        val etag: String? = null,
        val lastModified: String? = null,
    ) : FeedFetch

    data class NotModified(
        val etag: String? = null,
        val lastModified: String? = null,
    ) : FeedFetch
}

data class ParsedFeed(
    val title: String,
    val author: String,
    val description: String,
    val imageUrl: String,
    val link: String,
    val episodes: List<ParsedEpisode>,
)

data class ParsedEpisode(
    val guid: String,
    val title: String,
    val description: String,
    val audioUrl: String,
    val imageUrl: String,
    val pubDate: Long,
    val durationMs: Long,
    val chaptersUrl: String = "",
)

data class PodcastSearchResult(
    val feedUrl: String,
    val title: String,
    val author: String,
    val imageUrl: String,
)
