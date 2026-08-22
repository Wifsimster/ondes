package ovh.battistella.ondes.download

import java.io.File

/**
 * The sidecar record for a partially-downloaded episode: which version of the
 * remote file the bytes on disk came from, and how big that file is in full.
 *
 * Resuming a download means appending to bytes fetched minutes — or days —
 * earlier. Podcast enclosures are re-uploaded, re-encoded and served from
 * rotating CDN URLs, so appending blindly can splice two different recordings
 * into one unplayable file. Keeping the validator lets the retry send
 * `If-Range`, which makes the *server* decide whether the old bytes are still
 * part of the same file (opt. 5).
 *
 * Serialised as two lines (validator, total bytes) rather than JSON: it is
 * written on every download attempt and read back by exactly one caller.
 */
data class PartialDownload(
    /** The `ETag` (or `Last-Modified`) the partial bytes belong to; null if the server sent neither. */
    val validator: String?,
    /** Expected size of the complete file, or 0 when the server didn't say. */
    val totalBytes: Long,
) {
    fun serialize(): String = "${validator.orEmpty()}\n$totalBytes"

    /** Best-effort write — a failed sidecar only costs the next attempt its resume. */
    fun write(file: File) {
        runCatching { file.writeText(serialize()) }
    }

    companion object {
        fun parse(text: String): PartialDownload? {
            val lines = text.lines()
            if (lines.isEmpty()) return null
            val validator = lines[0].takeIf { it.isNotBlank() }
            val total = lines.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
            if (validator == null && total == 0L) return null
            return PartialDownload(validator, total)
        }

        fun read(file: File): PartialDownload? =
            runCatching { file.takeIf(File::isFile)?.readText() }.getOrNull()?.let(::parse)
    }
}
