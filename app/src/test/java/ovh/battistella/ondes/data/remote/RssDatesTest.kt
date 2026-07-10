package ovh.battistella.ondes.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

/**
 * Pins feed date parsing, the source of episode ordering. The headline bug it
 * guards: a literal-`Z` ISO timestamp must be read as UTC regardless of the
 * device timezone, and millisecond variants must parse instead of sinking an
 * episode to pubDate 0.
 */
class RssDatesTest {

    /** A fixed non-UTC default TZ so a timezone-naive parser would visibly skew. */
    private fun <T> withDefaultTz(id: String, block: () -> T): T {
        val original = TimeZone.getDefault()
        return try {
            TimeZone.setDefault(TimeZone.getTimeZone(id))
            block()
        } finally {
            TimeZone.setDefault(original)
        }
    }

    // 2021-01-01T00:00:00Z == 1609459200000 epoch millis.
    private val epochUtc = 1_609_459_200_000L

    @Test
    fun `rfc822 with numeric offset`() {
        assertEquals(epochUtc, RssDates.parse("Fri, 01 Jan 2021 00:00:00 +0000"))
    }

    @Test
    fun `iso8601 with Z offset token`() {
        assertEquals(epochUtc, RssDates.parse("2021-01-01T00:00:00Z"))
    }

    @Test
    fun `iso8601 literal Z is read as utc even under a non-utc device timezone`() {
        val parsed = withDefaultTz("America/Los_Angeles") {
            RssDates.parse("2021-01-01T00:00:00Z")
        }
        // A timezone-naive parser would offset this by 8 hours; it must not.
        assertEquals(epochUtc, parsed)
    }

    @Test
    fun `iso8601 with milliseconds parses instead of failing to zero`() {
        assertEquals(epochUtc, RssDates.parse("2021-01-01T00:00:00.000Z"))
    }

    @Test
    fun `iso8601 with milliseconds and explicit offset`() {
        assertEquals(epochUtc, RssDates.parse("2021-01-01T00:00:00.000+00:00"))
    }

    @Test
    fun `offset is honoured over the utc default`() {
        // 01:00 at +01:00 is midnight UTC.
        assertEquals(epochUtc, RssDates.parse("2021-01-01T01:00:00+01:00"))
    }

    @Test
    fun `blank and unparseable strings yield zero`() {
        assertEquals(0L, RssDates.parse(""))
        assertEquals(0L, RssDates.parse("   "))
        assertEquals(0L, RssDates.parse("not a date"))
    }
}
