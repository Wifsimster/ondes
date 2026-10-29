package ovh.battistella.ondes.data.remote

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object RssDates {

    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    private val WEEKDAY_PREFIX = Regex("^[A-Za-z]+,\\s*")

    private val FORMATS = listOf(
        "dd MMM yyyy HH:mm:ss Z",
        "dd MMM yyyy HH:mm:ss zzz",
        "dd MMM yyyy HH:mm Z",
        "dd MMM yyyy HH:mm zzz",
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

    fun parse(raw: String): Long {
        val t = raw.trim().replaceFirst(WEEKDAY_PREFIX, "")
        if (t.isEmpty()) return 0L
        for (pattern in FORMATS) {
            val parsed = runCatching {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                sdf.timeZone = UTC
                sdf.isLenient = false
                sdf.parse(t)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return 0L
    }
}
