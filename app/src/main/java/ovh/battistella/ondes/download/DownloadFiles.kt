package ovh.battistella.ondes.download

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Single source of truth for where an episode's downloaded audio lives on disk.
 *
 * Shared by [EpisodeDownloadWorker] (which writes it), [DownloadManager] (which
 * deletes it on cancel) and the startup orphan sweep, so all three agree on the
 * exact path without threading it through the database.
 *
 * File names are a SHA-256 of the episode id: the previous 32-bit
 * `String.hashCode()` scheme collided often enough that two episodes could
 * cross-wire onto the same file (issue P2). A partial download is written to a
 * sibling `.part` file and only renamed onto the final [target] once it is
 * verified complete (issue P0-5), so a truncated stream can never be mistaken
 * for a finished download.
 */
object DownloadFiles {

    private const val DIR = "downloads"
    private const val SUFFIX = ".audio"
    private const val PART_SUFFIX = ".audio.part"

    fun dir(context: Context): File = File(context.filesDir, DIR)

    fun target(context: Context, episodeId: String): File =
        File(dir(context), hash(episodeId) + SUFFIX)

    fun partFile(context: Context, episodeId: String): File =
        File(dir(context), hash(episodeId) + PART_SUFFIX)

    /** Whether [file] is one of our download artifacts (final or partial). */
    fun isDownloadArtifact(file: File): Boolean =
        file.name.endsWith(SUFFIX) || file.name.endsWith(PART_SUFFIX)

    fun hash(episodeId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(episodeId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
