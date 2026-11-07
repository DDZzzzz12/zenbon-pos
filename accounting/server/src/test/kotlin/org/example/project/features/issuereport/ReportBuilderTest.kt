package org.example.project.features.issuereport

import org.example.project.core.domain.dto.issuereport.IssueReportDto
import org.example.project.features.issuereport.data.service.ReportBuilder
import org.example.project.features.issuereport.domain.models.ReportContext
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("ReportBuilder")
class ReportBuilderTest {
    private val context =
        ReportContext(
            appVersion = "1.1.2",
            platform = "Linux 6.1 / Java 21",
            source = IssueReportDto.Source.ADMIN,
            screen = "Documents",
            timestamp = Instant.parse("2026-10-10T12:00:00Z"),
            shopName = "Zum Anker",
            reporterRole = "MANAGER",
        )

    @Test
    fun `body carries the description then the context block`() {
        val built = ReportBuilder.build("Export fails", "Clicking Save gives HTTP 500", context, logExcerpt = null)
        assertEquals("Export fails", built.title)
        assertTrue(built.body.startsWith("Clicking Save gives HTTP 500"), built.body)
        for (needle in listOf("1.1.2", "Linux 6.1 / Java 21", "ADMIN", "Documents", "2026-10-10T12:00:00Z", "Zum Anker", "MANAGER")) {
            assertTrue(built.body.contains(needle), "missing $needle in:\n${built.body}")
        }
        assertFalse(built.body.contains("Server log"), built.body)
    }

    @Test
    fun `title and description are scrubbed`() {
        val built = ReportBuilder.build("mail max@example.de", "bill 1234567890 failed", context, null)
        assertEquals("mail [email]", built.title)
        assertTrue(built.body.contains("bill [number] failed"), built.body)
    }

    @Test
    fun `a log excerpt is scrubbed and fenced`() {
        val built = ReportBuilder.build("T", "D", context, "GET /x Bearer abc.def failed for max@example.de")
        assertTrue(built.body.contains("Server log"), built.body)
        assertTrue(built.body.contains("Bearer [redacted]"), built.body)
        assertFalse(built.body.contains("abc.def"), built.body)
        assertFalse(built.body.contains("max@example.de"), built.body)
        assertTrue(built.body.contains("```"), built.body)
    }

    @Test
    fun `a missing screen or shop is omitted rather than printed as null`() {
        val built = ReportBuilder.build("T", "D", context.copy(screen = null, shopName = null), null)
        assertFalse(built.body.contains("null"), built.body)
    }

    @Test
    fun `mentions in operator text are neutralised but the log excerpt is left as scrubbed`() {
        val built =
            ReportBuilder.build(
                "ping @octocat",
                "cc @DDZzzzz12 and mail max@example.de",
                context.copy(screen = "@team"),
                "worker @Scheduled failed",
            )
        assertEquals("ping @\u200Boctocat", built.title)
        assertTrue(built.body.contains("cc @\u200BDDZzzzz12 and mail [email]"), built.body)
        assertTrue(built.body.contains("- Screen: @\u200Bteam"), built.body)
        val fence = built.body.substringAfter("```\n").substringBefore("\n```")
        assertEquals("worker @Scheduled failed", fence)
        assertFalse(Regex("@(?!\u200B)").containsMatchIn(built.body.substringBefore("```")), built.body)
    }
}
