package ovh.battistella.ondes.data.remote

/**
 * The outcome of a conditional feed fetch: either fresh content, or the
 * server's confirmation that nothing changed since the stored validators.
 */
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
    /** Podcasting 2.0 `<podcast:chapters>` JSON URL, if the feed provides one. */
    val chaptersUrl: String = "",
)

/** A podcast returned by the search service (iTunes). */
data class PodcastSearchResult(
    val feedUrl: String,
    val title: String,
    val author: String,
    val imageUrl: String,
)
