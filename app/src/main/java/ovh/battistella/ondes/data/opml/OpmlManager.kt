package ovh.battistella.ondes.data.opml

import android.util.Xml
import ovh.battistella.ondes.data.repository.PodcastRepository
import ovh.battistella.ondes.util.isHttpUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** A single `<outline>` from an OPML file. */
data class OpmlEntry(val title: String, val feedUrl: String)

/**
 * Import / export podcast subscriptions as OPML — the universal, lock-in-free
 * interchange format every podcast app speaks. Built on the platform XML APIs,
 * so it adds no dependency and keeps everything on-device.
 */
@Singleton
class OpmlManager @Inject constructor(
    private val repository: PodcastRepository,
) {
    /** Serialize the current subscriptions to an OPML document. */
    suspend fun export(output: OutputStream): Int = withContext(Dispatchers.IO) {
        val subs = repository.getSubscriptionsOnce()
        val serializer = Xml.newSerializer()
        serializer.setOutput(output, "UTF-8")
        serializer.startDocument("UTF-8", true)
        serializer.startTag(null, "opml")
        serializer.attribute(null, "version", "2.0")

        serializer.startTag(null, "head")
        serializer.startTag(null, "title")
        serializer.text("Ondes subscriptions")
        serializer.endTag(null, "title")
        serializer.endTag(null, "head")

        serializer.startTag(null, "body")
        subs.forEach { podcast ->
            serializer.startTag(null, "outline")
            serializer.attribute(null, "type", "rss")
            serializer.attribute(null, "text", podcast.title)
            serializer.attribute(null, "title", podcast.title)
            serializer.attribute(null, "xmlUrl", podcast.feedUrl)
            if (podcast.link.isNotBlank()) serializer.attribute(null, "htmlUrl", podcast.link)
            serializer.endTag(null, "outline")
        }
        serializer.endTag(null, "body")

        serializer.endTag(null, "opml")
        serializer.endDocument()
        serializer.flush()
        subs.size
    }

    /**
     * Parse an OPML document and subscribe to every feed it lists, skipping any
     * already-subscribed. Returns how many feeds were successfully added.
     */
    suspend fun import(input: InputStream): Int = withContext(Dispatchers.IO) {
        val alreadySubscribed = repository.getSubscriptionsOnce().map { it.feedUrl }.toSet()
        val entries = parse(input).filter { it.feedUrl !in alreadySubscribed }
        // Fetched with bounded concurrency: importing a 50-feed OPML one feed at
        // a time meant 50 round-trips back to back (opt. 2).
        repository.subscribeAll(entries.map { it.feedUrl }).size
    }

    private fun parse(input: InputStream): List<OpmlEntry> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        val entries = mutableListOf<OpmlEntry>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG &&
                parser.name.equals("outline", ignoreCase = true)
            ) {
                val xmlUrl = attr(parser, "xmlUrl")?.trim()
                // Ignore non-http(s) feed URLs an OPML file might smuggle in.
                if (xmlUrl != null && isHttpUrl(xmlUrl)) {
                    val title = attr(parser, "title")
                        ?: attr(parser, "text")
                        ?: xmlUrl
                    entries += OpmlEntry(title.trim(), xmlUrl)
                }
            }
            event = parser.next()
        }
        return entries.distinctBy { it.feedUrl }
    }

    /** Case-insensitive attribute lookup (OPML casing varies in the wild). */
    private fun attr(parser: XmlPullParser, name: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i).lowercase(Locale.US) == name.lowercase(Locale.US)) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }
}
