package ovh.battistella.ondes.data.remote

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Pure, timezone-correct parsing of the date strings found in podcast feeds
 * (RFC-822 for RSS `pubDate`, ISO-8601 for Atom `published`/`updated`).
 *
 * Kept separate and Android-free so the fiddly format matrix can be pinned by
 * host unit tests. Two bugs it exists to prevent (issue P1-6):
 *  - An ISO timestamp ending in a literal `Z` was parsed in the *device's* local
 *    timezone, skewing episode ordering by up to ±14 h. Every formatter here
 *    defaults to UTC, so a zoneless or literal-`Z` timestamp is read as UTC;
 *    formats with a real offset token still honour the offset in the input.
 *  - Millisecond variants (`…ss.SSS…`) matched nothing, sinking those episodes
 *    to `pubDate = 0`; they are covered explicitly below.
 */
object RssDates {

    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    // Ordered most-specific first. Offset tokens: `Z` (RFC-822 numeric, e.g.
    // +0000), `zzz` (named), `XX` (+0000), `XXX` (+00:00); `X` variants and the
    // literal `'Z'`/zoneless forms fall back to the formatter's UTC default.
    private val FORMATS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, dd MMM yyyy HH:mm Z",
        "EEE, dd MMM yyyy HH:mm zzz",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ssXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd",
    )

    /** Epoch millis for [raw], or 0 when it is blank or matches no known format. */
    fun parse(raw: String): Long {
        val t = raw.trim()
        if (t.isEmpty()) return 0L
        for (pattern in FORMATS) {
            val parsed = runCatching {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                // Deterministic: a zoneless / literal-Z timestamp is UTC, not
                // whatever the device happens to be set to. An explicit offset in
                // the input still wins, as SimpleDateFormat applies it over this.
                sdf.timeZone = UTC
                sdf.isLenient = false
                sdf.parse(t)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return 0L
    }
}
