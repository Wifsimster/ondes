package ovh.battistella.ondes.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class RssDatesTest {

    private fun <T> withDefaultTz(id: String, block: () -> T): T {
        val original = TimeZone.getDefault()
        return try {
            TimeZone.setDefault(TimeZone.getTimeZone(id))
            block()
        } finally {
            TimeZone.setDefault(original)
        }
    }

    private val epochUtc = 1_609_459_200_000L

    @Test
    fun `rfc822 with numeric offset`() {
        assertEquals(epochUtc, RssDates.parse("Fri, 01 Jan 2021 00:00:00 +0000"))
    }

    @Test
    fun `rfc822 with a wrong weekday still parses`() {
        assertEquals(epochUtc, RssDates.parse("Mon, 01 Jan 2021 00:00:00 +0000"))
    }

    @Test
    fun `rfc822 without a weekday parses`() {
        assertEquals(epochUtc, RssDates.parse("01 Jan 2021 00:00:00 GMT"))
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
        assertEquals(epochUtc, RssDates.parse("2021-01-01T01:00:00+01:00"))
    }

    @Test
    fun `blank and unparseable strings yield zero`() {
        assertEquals(0L, RssDates.parse(""))
        assertEquals(0L, RssDates.parse("   "))
        assertEquals(0L, RssDates.parse("not a date"))
    }
}
