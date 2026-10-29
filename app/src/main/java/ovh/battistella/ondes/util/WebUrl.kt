package ovh.battistella.ondes.util

fun isHttpUrl(url: String?): Boolean {
    val u = url?.trim()?.lowercase() ?: return false
    return u.startsWith("http://") || u.startsWith("https://")
}

fun httpUrlOrEmpty(url: String?): String =
    url?.trim()?.takeIf { isHttpUrl(it) } ?: ""
