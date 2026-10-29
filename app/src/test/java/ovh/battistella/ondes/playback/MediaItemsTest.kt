package ovh.battistella.ondes.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MediaItemsTest {

    @Test
    fun existingLocalFileWins() {
        val file = File.createTempFile("ondes-episode", ".mp3").apply { deleteOnExit() }

        val source = MediaItems.resolveAudioSource(file.path, "https://cdn.example.com/ep.mp3")

        assertTrue(source is AudioSource.Local)
        assertEquals(file, (source as AudioSource.Local).file)
    }

    @Test
    fun fallsBackToRemoteWhenLocalFileMissing() {
        val missing = File.createTempFile("ondes-missing", ".mp3").apply { delete() }

        val source = MediaItems.resolveAudioSource(missing.path, "https://cdn.example.com/ep.mp3")

        assertEquals(AudioSource.Remote("https://cdn.example.com/ep.mp3"), source)
    }

    @Test
    fun usesRemoteWhenNoLocalPath() {
        val source = MediaItems.resolveAudioSource(null, "http://cdn.example.com/ep.mp3")

        assertEquals(AudioSource.Remote("http://cdn.example.com/ep.mp3"), source)
    }

    @Test
    fun rejectsNonHttpRemoteUrls() {
        assertNull(MediaItems.resolveAudioSource(null, "file:///data/data/secret"))
        assertNull(MediaItems.resolveAudioSource(null, "content://media/external/audio"))
        assertNull(MediaItems.resolveAudioSource(null, ""))
    }

    @Test
    fun missingLocalFileAndUnsafeRemoteYieldsNothing() {
        val missing = File.createTempFile("ondes-missing", ".mp3").apply { delete() }

        assertNull(MediaItems.resolveAudioSource(missing.path, "file:///etc/passwd"))
    }
}
