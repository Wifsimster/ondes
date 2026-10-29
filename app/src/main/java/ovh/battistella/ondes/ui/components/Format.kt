package ovh.battistella.ondes.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ovh.battistella.ondes.R
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = TimeUnit.SECONDS.toHours(totalSec)
    val m = TimeUnit.SECONDS.toMinutes(totalSec) % 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

@Composable
fun durationLabel(ms: Long): String {
    if (ms <= 0) return ""
    val totalMin = ms / 60000
    val h = (totalMin / 60).toInt()
    val m = (totalMin % 60).toInt()
    return if (h > 0) stringResource(R.string.duration_hours_minutes, h, m)
    else stringResource(R.string.minutes_short, m)
}

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

fun stripHtml(html: String): String =
    html.replace(Regex("<[^>]*>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&#39;", "'")
        .replace("&quot;", "\"")
        .replace(Regex("\\s+"), " ")
        .trim()
