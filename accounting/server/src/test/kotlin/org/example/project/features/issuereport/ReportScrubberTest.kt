package org.example.project.features.issuereport

import org.example.project.features.issuereport.data.service.ReportScrubber
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@DisplayName("ReportScrubber")
class ReportScrubberTest {
    @Test
    fun `strips bearer tokens`() {
        val out = ReportScrubber.scrub("Authorization: Bearer abc.DEF-123_xyz/+= failed")
        assertFalse(out.contains("abc.DEF"), out)
        assertEquals("Authorization: Bearer [redacted] failed", out)
    }

    @Test
    fun `strips a bare JWT`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NSJ9.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
        assertEquals("token=[jwt]", ReportScrubber.scrub("token=$jwt"))
    }

    @Test
    fun `strips GitHub tokens`() {
        val out = ReportScrubber.scrub("pat github_pat_11ABCDEFG0abcdefghijklmnop_xyz and ghp_abcdefghijklmnopqrstuvwxyz0123456789")
        assertFalse(out.contains("github_pat_"), out)
        assertFalse(out.contains("ghp_"), out)
    }

    @Test
    fun `strips email addresses`() {
        assertEquals("contact [email] now", ReportScrubber.scrub("contact max.mustermann+pos@example.de now"))
    }

    @Test
    fun `strips IBANs compact and spaced`() {
        assertEquals("[iban]", ReportScrubber.scrub("DE89370400440532013000"))
        assertEquals("[iban]", ReportScrubber.scrub("DE89 3704 0044 0532 0130 00"))
    }

    @Test
    fun `strips digit runs of nine or more but keeps shorter numbers`() {
        assertEquals("bill [number] total 1234", ReportScrubber.scrub("bill 1234567890 total 1234"))
        assertEquals("order 12345678", ReportScrubber.scrub("order 12345678"))
    }

    @Test
    fun `leaves ordinary text alone`() {
        val text = "Z-Bon export failed on screen Documents after clicking Save (HTTP 500)"
        assertEquals(text, ReportScrubber.scrub(text))
    }
}
