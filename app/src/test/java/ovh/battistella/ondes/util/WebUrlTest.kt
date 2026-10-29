package ovh.battistella.ondes.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUrlTest {

    @Test
    fun acceptsHttpAndHttps() {
        assertTrue(isHttpUrl("http://example.com/feed.xml"))
        assertTrue(isHttpUrl("https://example.com/a.mp3"))
        assertTrue(isHttpUrl("  HTTPS://Example.com/A.mp3  "))
    }

    @Test
    fun rejectsNonHttpSchemes() {
        assertFalse(isHttpUrl("file:///data/data/ovh.battistella.ondes/databases/ondes.db"))
        assertFalse(isHttpUrl("content://media/external/audio/media/1"))
        assertFalse(isHttpUrl("javascript:alert(1)"))
        assertFalse(isHttpUrl("ftp://example.com/a.mp3"))
        assertFalse(isHttpUrl("data:audio/mp3;base64,AAAA"))
    }

    @Test
    fun rejectsBlankOrNull() {
        assertFalse(isHttpUrl(null))
        assertFalse(isHttpUrl(""))
        assertFalse(isHttpUrl("   "))
    }

    @Test
    fun httpUrlOrEmptyTrimsAndFilters() {
        assertEquals("https://example.com/a.mp3", httpUrlOrEmpty("  https://example.com/a.mp3 "))
        assertEquals("", httpUrlOrEmpty("file:///etc/passwd"))
        assertEquals("", httpUrlOrEmpty(null))
        assertEquals("", httpUrlOrEmpty(""))
    }
}
