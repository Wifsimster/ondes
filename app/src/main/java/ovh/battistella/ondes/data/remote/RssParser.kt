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

/**
 * Streaming RSS / Atom-ish parser for podcast feeds, built on the platform
 * [XmlPullParser] (no third-party dependency). Tolerant of the messy real
 * world: missing fields fall back to sensible defaults.
 */
@Singleton
class RssParser @Inject constructor(
    private val client: OkHttpClient,
) {
    fun fetchAndParse(feedUrl: String): ParsedFeed {
        val request = Request.Builder()
            .url(feedUrl)
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} fetching $feedUrl")
            val body = response.body ?: error("Empty body for $feedUrl")
            return parse(body.byteStream())
        }
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

        // Per-item accumulators
        var iTitle = ""
        var iDesc = ""
        var iGuid = ""
        var iAudio = ""
        var iImage = ""
        var iPub = 0L
        var iDuration = 0L
        var iChapters = ""

        // A malformed tail (bad entity, mismatched tag) must not throw away the
        // episodes already parsed — otherwise one stray `&nbsp;` makes a whole
        // feed permanently un-subscribable (issue P1-7). Keep what we have.
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
                            // Only http(s) enclosures are playable; a feed pointing
                            // the audio at a local file:// / content:// URI is dropped.
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

    /** Returns a (key,value) for recognized item-level tags, or null. */
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
        // Either seconds ("3600") or HH:MM:SS / MM:SS
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

    /**
     * Read the text content of the element the parser is positioned on, tolerant
     * of mixed content: nested tags (e.g. raw HTML in a description that isn't in
     * a CDATA section) are skipped rather than throwing, unlike [XmlPullParser.nextText]
     * which aborts the whole parse on any element child (issue P1-7). Leaves the
     * parser on the element's END_TAG, matching `nextText()` so the caller's loop
     * advances correctly.
     */
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
    }
}
