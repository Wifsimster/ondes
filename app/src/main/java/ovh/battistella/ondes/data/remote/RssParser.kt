package ovh.battistella.ondes.data.remote

import android.util.Xml
import ovh.battistella.ondes.util.httpUrlOrEmpty
import ovh.battistella.ondes.util.isHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RssParser @Inject constructor(
    private val client: OkHttpClient,
) {
    fun fetch(feedUrl: String, etag: String? = null, lastModified: String? = null): FeedFetch {
        val request = Request.Builder()
            .url(feedUrl)
            .header("User-Agent", USER_AGENT)
            .apply {
                etag?.takeIf { it.isNotBlank() }?.let { header("If-None-Match", it) }
                lastModified?.takeIf { it.isNotBlank() }?.let { header("If-Modified-Since", it) }
            }
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == HTTP_NOT_MODIFIED) {
                return FeedFetch.NotModified(
                    etag = response.header("ETag") ?: etag,
                    lastModified = response.header("Last-Modified") ?: lastModified,
                )
            }
            if (!response.isSuccessful) error("HTTP ${response.code} fetching $feedUrl")
            val body = response.body ?: error("Empty body for $feedUrl")
            return FeedFetch.Updated(
                feed = parse(body.byteStream()),
                etag = response.header("ETag"),
                lastModified = response.header("Last-Modified"),
            )
        }
    }

    fun fetchAndParse(feedUrl: String): ParsedFeed = when (val result = fetch(feedUrl)) {
        is FeedFetch.Updated -> result.feed
        is FeedFetch.NotModified -> error("Unexpected 304 for un-conditional fetch of $feedUrl")
    }

    fun parse(input: InputStream): ParsedFeed {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var channelTitle = ""
        var channelAuthor = ""
        var channelDesc = ""
        var channelImage = ""
        var channelLink = ""
        val episodes = mutableListOf<ParsedEpisode>()

        var event = parser.eventType
        var insideItem = false
        var insideImage = false

        var iTitle = ""
        var iDesc = ""
        var iGuid = ""
        var iAudio = ""
        var iImage = ""
        var iPub = 0L
        var iDuration = 0L
        var iChapters = ""

        runCatching {
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.lowercase(Locale.US)
                    when {
                        name == "item" || name == "entry" -> {
                            insideItem = true
                            iTitle = ""; iDesc = ""; iGuid = ""
                            iAudio = ""; iImage = ""; iPub = 0L; iDuration = 0L
                            iChapters = ""
                        }
                        name == "image" && !insideItem -> insideImage = true
                        insideItem -> readItemTag(parser, name).let { r ->
                            if (r != null) when (r.first) {
                                "title" -> iTitle = r.second
                                "description" -> if (iDesc.isEmpty()) iDesc = r.second
                                "content:encoded" -> iDesc = r.second
                                "guid" -> iGuid = r.second
                                "pubdate" -> iPub = RssDates.parse(r.second)
                                "duration" -> iDuration = parseDuration(r.second)
                                "audio" -> iAudio = r.second
                                "image" -> iImage = r.second
                                "chaptersurl" -> iChapters = r.second
                            }
                        }
                        insideImage -> {
                            if (name == "url") channelImage = readText(parser)
                        }
                        else -> when (name) {
                            "title" -> channelTitle = readText(parser)
                            "description", "subtitle" ->
                                if (channelDesc.isEmpty()) channelDesc = readText(parser)
                            "author" -> channelAuthor = readText(parser)
                            "itunes:author" -> if (channelAuthor.isEmpty())
                                channelAuthor = readText(parser)
                            "link" -> if (channelLink.isEmpty())
                                channelLink = readText(parser)
                            "itunes:image" -> if (channelImage.isEmpty())
                                channelImage = parser.getAttributeValue(null, "href")?.trim().orEmpty()
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name.lowercase(Locale.US)
                    when (name) {
                        "item", "entry" -> {
                            if (isHttpUrl(iAudio)) {
                                episodes += ParsedEpisode(
                                    guid = iGuid.ifEmpty { iAudio },
                                    title = iTitle.ifEmpty { "Untitled" },
                                    description = iDesc,
                                    audioUrl = iAudio.trim(),
                                    imageUrl = httpUrlOrEmpty(iImage),
                                    pubDate = iPub,
                                    durationMs = iDuration,
                                    chaptersUrl = httpUrlOrEmpty(iChapters),
                                )
                            }
                            insideItem = false
                        }
                        "image" -> insideImage = false
                    }
                }
            }
            event = parser.next()
        }
        }

        return ParsedFeed(
            title = channelTitle.ifEmpty { "Podcast" },
            author = channelAuthor,
            description = channelDesc,
            imageUrl = httpUrlOrEmpty(channelImage),
            link = channelLink,
            episodes = episodes,
        )
    }

    private fun readItemTag(parser: XmlPullParser, name: String): Pair<String, String>? = when (name) {
        "title" -> "title" to readText(parser)
        "description", "summary", "itunes:summary" -> "description" to readText(parser)
        "content:encoded" -> "content:encoded" to readText(parser)
        "guid", "id" -> "guid" to readText(parser)
        "pubdate", "published", "updated" -> "pubdate" to readText(parser)
        "itunes:duration" -> "duration" to readText(parser)
        "enclosure" -> {
            val url = parser.getAttributeValue(null, "url")?.trim().orEmpty()
            if (url.isNotEmpty()) "audio" to url else null
        }
        "itunes:image" -> {
            val href = parser.getAttributeValue(null, "href")?.trim().orEmpty()
            if (href.isNotEmpty()) "image" to href else null
        }
        "podcast:chapters" -> {
            val url = parser.getAttributeValue(null, "url")?.trim().orEmpty()
            if (url.isNotEmpty()) "chaptersurl" to url else null
        }
        else -> null
    }

    private fun parseDuration(raw: String): Long {
        val t = raw.trim()
        if (t.isEmpty()) return 0L
        return if (t.contains(":")) {
            val parts = t.split(":").mapNotNull { it.trim().toLongOrNull() }
            when (parts.size) {
                3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
                2 -> (parts[0] * 60 + parts[1]) * 1000
                else -> 0L
            }
        } else {
            (t.toDoubleOrNull()?.toLong() ?: 0L) * 1000
        }
    }

    private fun readText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        runCatching {
            while (depth > 0) {
                when (parser.next()) {
                    XmlPullParser.START_TAG -> depth++
                    XmlPullParser.END_TAG -> depth--
                    XmlPullParser.TEXT, XmlPullParser.CDSECT ->
                        if (depth == 1) sb.append(parser.text)
                    XmlPullParser.END_DOCUMENT -> return@runCatching
                }
            }
        }
        return sb.toString().trim()
    }

    companion object {
        private const val USER_AGENT = "Ondes/1.0 (Android podcast app)"
        private const val HTTP_NOT_MODIFIED = 304
    }
}
