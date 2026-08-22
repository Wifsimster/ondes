package ovh.battistella.ondes.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The sidecar that decides whether a partial download may be resumed. Getting
 * this wrong means appending fresh bytes onto a stale file, so the round trip is
 * pinned here — including the cases where there is nothing to resume from.
 */
class PartialDownloadTest {

    @Test
    fun `round trips a validator and size`() {
        val record = PartialDownload(validator = "\"abc123\"", totalBytes = 4_096)
        val parsed = PartialDownload.parse(record.serialize())
        assertEquals(record, parsed)
    }

    @Test
    fun `keeps the size when the server sent no validator`() {
        val parsed = PartialDownload.parse(PartialDownload(null, 900).serialize())
        assertNull(parsed?.validator)
        assertEquals(900L, parsed?.totalBytes)
    }

    @Test
    fun `an empty or unparseable record yields nothing to resume from`() {
        assertNull(PartialDownload.parse(""))
        assertNull(PartialDownload.parse("\n"))
        assertNull(PartialDownload.parse("\nnot-a-number"))
    }

    @Test
    fun `a validator containing spaces survives`() {
        // Last-Modified is a date with spaces and commas.
        val record = PartialDownload("Wed, 21 Oct 2026 07:28:00 GMT", 12)
        assertEquals(record, PartialDownload.parse(record.serialize()))
    }
}
