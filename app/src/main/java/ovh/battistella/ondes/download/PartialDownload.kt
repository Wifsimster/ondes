package ovh.battistella.ondes.download

import java.io.File

data class PartialDownload(
    val validator: String?,
    val totalBytes: Long,
) {
    fun serialize(): String = "${validator.orEmpty()}\n$totalBytes"

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
