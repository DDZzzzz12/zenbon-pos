package org.example.project.features.maintenance

import org.example.project.features.maintenance.data.logs.LogEntryMatcher
import org.example.project.features.maintenance.data.logs.LogLevel
import org.example.project.features.maintenance.data.logs.LogQuery
import org.example.project.features.maintenance.data.logs.LogRecord
import org.example.project.features.maintenance.data.logs.LogSourceKind
import org.example.project.features.maintenance.domain.dto.MaintenanceDto
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogEntryMatcherTest {
    private fun record(
        at: String = "2026-10-09T06:00:00Z",
        level: LogLevel = LogLevel.INFO,
        traceId: String? = "t-1",
        code: String? = "AUTH_FORBIDDEN",
        status: Int? = 403,
    ) = LogRecord(
        LogSourceKind.REJECTED,
        Instant.parse(at),
        level,
        "rejected",
        traceId,
        code,
        status,
        null,
        null,
        null,
        "m",
        null,
        null,
        null,
        null,
    )

    private fun entry(message: String = "Employee # saw it") =
        MaintenanceDto.LogEntryDto(source = "rejected", timestamp = "x", level = "INFO", message = message, path = "/api/orders/{id}")

    @Test
    fun `trace id is an exact match`() {
        assertTrue(LogEntryMatcher(LogQuery(traceId = "t-1")).matchesStructure(record()))
        assertFalse(LogEntryMatcher(LogQuery(traceId = "t")).matchesStructure(record()))
        assertFalse(LogEntryMatcher(LogQuery(traceId = "t-1")).matchesStructure(record(traceId = null)))
    }

    @Test
    fun `the time range is inclusive at both ends`() {
        val q = LogQuery(from = Instant.parse("2026-10-09T06:00:00Z"), to = Instant.parse("2026-10-09T06:00:00Z"))
        assertTrue(LogEntryMatcher(q).matchesStructure(record()))
        assertFalse(LogEntryMatcher(q).matchesStructure(record(at = "2026-10-09T05:59:59Z")))
        assertFalse(LogEntryMatcher(q).matchesStructure(record(at = "2026-10-09T06:00:01Z")))
    }

    @Test
    fun `level is a minimum`() {
        val q = LogQuery(minLevel = LogLevel.WARN)
        assertFalse(LogEntryMatcher(q).matchesStructure(record(level = LogLevel.INFO)))
        assertTrue(LogEntryMatcher(q).matchesStructure(record(level = LogLevel.ERROR)))
    }

    @Test
    fun `code and status are exact`() {
        assertTrue(LogEntryMatcher(LogQuery(code = "AUTH_FORBIDDEN", status = 403)).matchesStructure(record()))
        assertFalse(LogEntryMatcher(LogQuery(code = "OTHER")).matchesStructure(record()))
        assertFalse(LogEntryMatcher(LogQuery(status = 500)).matchesStructure(record()))
    }

    @Test
    fun `free text searches the SCRUBBED entry, so it cannot confirm a hidden value`() {
        // The raw message was "Employee 1042 saw it"; the DTO holds "Employee # saw it".
        assertTrue(LogEntryMatcher(LogQuery(text = "saw it")).matchesText(entry()))
        assertTrue(LogEntryMatcher(LogQuery(text = "SAW IT")).matchesText(entry()), "case-insensitive")
        assertFalse(LogEntryMatcher(LogQuery(text = "1042")).matchesText(entry()), "the hidden number must not be findable")
        assertTrue(LogEntryMatcher(LogQuery(text = "/api/orders")).matchesText(entry()), "the normalised path is searchable")
    }

    @Test
    fun `no text filter matches everything`() {
        assertTrue(LogEntryMatcher(LogQuery()).matchesText(entry()))
    }
}
