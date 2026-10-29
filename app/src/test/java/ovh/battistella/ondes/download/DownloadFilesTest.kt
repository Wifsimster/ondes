package ovh.battistella.ondes.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DownloadFilesTest {

    @Test
    fun hashIsStableForTheSameId() {
        assertEquals(DownloadFiles.hash("ep-42"), DownloadFiles.hash("ep-42"))
    }

    @Test
    fun distinctIdsHashDistinctly() {
        assertNotEquals(DownloadFiles.hash("ep-1"), DownloadFiles.hash("ep-2"))
    }

    @Test
    fun hashIsFullLengthSha256Hex() {
        val hash = DownloadFiles.hash("https://cdn.example.com/feed::guid-123")
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun recognisesOwnArtifacts() {
        assertTrue(DownloadFiles.isDownloadArtifact(File("/x/${DownloadFiles.hash("a")}.audio")))
        assertTrue(DownloadFiles.isDownloadArtifact(File("/x/${DownloadFiles.hash("a")}.audio.part")))
    }

    @Test
    fun ignoresUnrelatedFiles() {
        assertFalse(DownloadFiles.isDownloadArtifact(File("/x/cover.jpg")))
        assertFalse(DownloadFiles.isDownloadArtifact(File("/x/episode.mp3")))
    }
}
