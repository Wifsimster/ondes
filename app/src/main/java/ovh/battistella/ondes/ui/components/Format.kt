package ovh.battistella.ondes.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ovh.battistella.ondes.R
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** "1:02:03" or "12:34". */
fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = TimeUnit.SECONDS.toHours(totalSec)
    val m = TimeUnit.SECONDS.toMinutes(totalSec) % 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

/**
 * Compact duration label, e.g. "1h 02m" or "34 min", in the user's language.
 * The unit letters used to be hardcoded English inside a formatter (issue P2),
 * so a German or Spanish list read "1h 02m" whatever the device language.
 */
@Composable
fun durationLabel(ms: Long): String {
    if (ms <= 0) return ""
    val totalMin = ms / 60000
    val h = (totalMin / 60).toInt()
    val m = (totalMin % 60).toInt()
    return if (h > 0) stringResource(R.string.duration_hours_minutes, h, m)
    else stringResource(R.string.minutes_short, m)
}

/**
 * Locale-formatted publication date.
 *
 * The formatter is cached per locale: building a [SimpleDateFormat] parses its
 * pattern and loads the locale's symbols, and this runs once per episode row —
 * once per row per recomposition — while scrolling (issue P2). [DateFormat] is
 * not thread-safe, so the cache is per-thread.
 */
fun formatDate(epochMs: Long): String {
    if (epochMs <= 0) return ""
    return dateFormatter().format(Date(epochMs))
}

private val dateFormatCache = ThreadLocal<Pair<Locale, DateFormat>>()

private fun dateFormatter(): DateFormat {
    val locale = Locale.getDefault()
    dateFormatCache.get()?.takeIf { it.first == locale }?.let { return it.second }
    val formatter = SimpleDateFormat("d MMM yyyy", locale)
    dateFormatCache.set(locale to formatter)
    return formatter
}

/** Strip HTML tags from feed descriptions for plain-text display. */
fun stripHtml(html: String): String =
    html.replace(Regex("<[^>]*>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&#39;", "'")
        .replace("&quot;", "\"")
        .replace(Regex("\\s+"), " ")
        .trim()
