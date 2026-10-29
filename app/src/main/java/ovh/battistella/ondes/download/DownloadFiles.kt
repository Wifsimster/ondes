package ovh.battistella.ondes.download

import android.content.Context
import java.io.File
import java.security.MessageDigest

object DownloadFiles {

    private const val DIR = "downloads"
    private const val SUFFIX = ".audio"
    private const val PART_SUFFIX = ".audio.part"
    private const val META_SUFFIX = ".audio.meta"

    fun dir(context: Context): File = File(context.filesDir, DIR)

    fun target(context: Context, episodeId: String): File =
        File(dir(context), hash(episodeId) + SUFFIX)

    fun partFile(context: Context, episodeId: String): File =
        File(dir(context), hash(episodeId) + PART_SUFFIX)

    fun metaFile(context: Context, episodeId: String): File =
        File(dir(context), hash(episodeId) + META_SUFFIX)

    fun isDownloadArtifact(file: File): Boolean =
        file.name.endsWith(SUFFIX) || file.name.endsWith(PART_SUFFIX) ||
            file.name.endsWith(META_SUFFIX)

    fun isPartialArtifact(file: File): Boolean =
        file.name.endsWith(PART_SUFFIX) || file.name.endsWith(META_SUFFIX)

    fun partialNames(episodeId: String): Set<String> =
        setOf(hash(episodeId) + PART_SUFFIX, hash(episodeId) + META_SUFFIX)

    fun hash(episodeId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(episodeId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
