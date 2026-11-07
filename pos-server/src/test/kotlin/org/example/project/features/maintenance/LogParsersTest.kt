package org.example.project.features.maintenance

import org.example.project.features.maintenance.data.logs.ClientErrorLogParser
import org.example.project.features.maintenance.data.logs.ErrorLogParser
import org.example.project.features.maintenance.data.logs.JsonLogParser
import org.example.project.features.maintenance.data.logs.LogLevel
import org.example.project.features.maintenance.data.logs.LogSourceKind
import org.example.project.testutils.LogFixtures
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.function.ThrowingSupplier
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogParsersTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    // ---- JSON lines -------------------------------------------------------------------------------

    @Test
    fun `a rejected json line is parsed into the structured fields`() {
        val line =
            LogFixtures.jsonLine(
                timestamp = "2026-10-09T08:00:00.123+02:00",
                message = "AUTH_FORBIDDEN 403 — Required capability: CATALOG_MANAGE",
                traceId = "trace-1",
                code = "AUTH_FORBIDDEN",
                status = 403,
                method = "GET",
                path = "/api/products/9",
                durationMs = 17,
            )
        val record = assertNotNull(JsonLogParser(LogSourceKind.REJECTED).parse(listOf(line)))
        assertEquals(Instant.parse("2026-10-09T06:00:00.123Z"), record.timestamp)
        assertEquals(LogLevel.INFO, record.level)
        assertEquals("rejected", record.logger)
        assertEquals("trace-1", record.traceId)
        assertEquals("AUTH_FORBIDDEN", record.code)
        assertEquals(403, record.statusCode)
        assertEquals("/api/products/9", record.path)
        assertEquals(17L, record.durationMs)
        assertEquals(LogSourceKind.REJECTED, record.source)
    }

    @Test
    fun `the employee and device ids in a json line are read past, not carried`() {
        val line =
            LogFixtures.jsonLine(
                timestamp = "2026-10-09T08:00:00+02:00",
                message = "x",
                employeeId = "emp-secret-123",
                deviceId = "dev-secret-9",
            )
        val record = assertNotNull(JsonLogParser(LogSourceKind.APPLICATION).parse(listOf(line)))
        assertFalse("emp-secret-123" in record.toString())
        assertFalse("dev-secret-9" in record.toString())
    }

    @Test
    fun `a json stack_trace becomes the exception text`() {
        val line =
            LogFixtures.jsonLine(
                "2026-10-09T08:00:00+02:00",
                "boom",
                level = "ERROR",
                stackTrace = "java.lang.IllegalStateException: x\n\tat a.B.c(B.kt:1)",
            )
        val record = assertNotNull(JsonLogParser(LogSourceKind.APPLICATION).parse(listOf(line)))
        assertEquals(LogLevel.ERROR, record.level)
        assertTrue("IllegalStateException" in record.exceptionText.orEmpty())
    }

    @Test
    fun `garbage and half-written json lines are skipped, not fatal`() {
        val parser = JsonLogParser(LogSourceKind.APPLICATION)
        assertNull(parser.parse(listOf("not json at all")))
        assertNull(parser.parse(listOf("""{"@timestamp":"2026-10-09T08:00""")))
        assertNull(parser.parse(listOf("""{"message":"no timestamp"}""")))
        assertNull(parser.parse(emptyList()))
    }

    // ---- error.log --------------------------------------------------------------------------------

    @Test
    fun `an error log entry carries its stack trace and is always ERROR`() {
        val block =
            LogFixtures.errorLogEntry(
                timestamp = "2026-10-09 08:10:00.000",
                traceId = "trace-3",
                code = "INTERNAL_ERROR",
                status = "500",
                logger = "StatusPages",
                message = "Request failed: boom",
                stackLines = listOf("java.lang.IllegalStateException: boom", "\tat a.B.c(B.kt:1)"),
            )
        val record = assertNotNull(ErrorLogParser(berlin).parse(block))
        assertEquals(LogLevel.ERROR, record.level)
        assertEquals(Instant.parse("2026-10-09T06:10:00Z"), record.timestamp)
        assertEquals("trace-3", record.traceId)
        assertEquals(500, record.statusCode)
        assertEquals("StatusPages", record.logger)
        assertEquals("Request failed: boom", record.message)
        assertTrue("at a.B.c(B.kt:1)" in record.exceptionText.orEmpty())
    }

    @Test
    fun `empty brackets mean no trace id and no code`() {
        val record = assertNotNull(ErrorLogParser(berlin).parse(LogFixtures.errorLogEntry("2026-10-09 08:10:00.000", message = "m")))
        assertNull(record.traceId)
        assertNull(record.code)
        assertNull(record.statusCode)
    }

    @Test
    fun `only a full header starts an entry - a stack line does not`() {
        val parser = ErrorLogParser(berlin)
        assertTrue(parser.isEntryStart("2026-10-09 08:10:00.000 [t] [c] [500] StatusPages - msg"))
        assertFalse(parser.isEntryStart("\tat org.example.Foo.bar(Foo.kt:12)"))
        assertFalse(parser.isEntryStart("Caused by: java.lang.IllegalStateException"))
        assertFalse(parser.isEntryStart("2026-10-09 08:10:00.000 something without brackets"))
    }

    // ---- client-error.log -------------------------------------------------------------------------

    @Test
    fun `a client error keeps its ids and app and drops screen, employee and device`() {
        val block =
            LogFixtures.clientErrorEntry(
                timestamp = "2026-10-09 08:11:00.000",
                message = "Order failed",
                clientApp = "admin",
                clientErrorId = "c-77",
                traceId = "trace-1",
                code = "VALIDATION_FAILED",
                httpStatus = "400",
                screen = "OrdersScreen",
                employee = "1042",
                device = "dev-secret-9",
                version = "1.1.0",
                exceptionType = "java.lang.IllegalStateException",
                exceptionMessage = "boom 1042",
                stackLines = listOf("    at org.example.Foo.bar(Foo.kt:12)"),
            )
        val record = assertNotNull(ClientErrorLogParser(berlin).parse(block))
        assertEquals(LogSourceKind.CLIENT_ERROR, record.source)
        assertEquals("admin", record.clientApp)
        assertEquals("c-77", record.clientErrorId)
        assertEquals("1.1.0", record.appVersion)
        assertEquals("trace-1", record.traceId)
        assertEquals(400, record.statusCode)
        assertEquals("Order failed", record.message)
        assertTrue("IllegalStateException" in record.exceptionText.orEmpty())
        listOf("OrdersScreen", "dev-secret-9", "1042").forEach { secret ->
            assertFalse(secret in record.copy(exceptionText = null).toString(), "$secret leaked into the record")
        }
    }

    @Test
    fun `a pathological client error header is rejected in linear time`() {
        // screen= is client-supplied. With lazy `.*?` groups and many separator look-alikes and no
        // " - " the old header pattern backtracked cubically: a 24 KB line took ~14 s.
        val head = "2026-10-09 08:11:00.000 [admin] [c-1] [t-1] [] [] screen="
        val line = head + " employee= device= version=".repeat(64 * 1024 / 27)
        assertTrue(line.length >= 60_000)
        val parser = ClientErrorLogParser(berlin)
        val outcome =
            assertTimeoutPreemptively(Duration.ofSeconds(2), ThrowingSupplier { parser.isEntryStart(line) to parser.parse(listOf(line)) })
        assertFalse(outcome.first)
        assertNull(outcome.second)
        // and a normal line still parses
        val normal = LogFixtures.clientErrorEntry("2026-10-09 08:11:00.000", message = "Order failed", traceId = "t-9", version = "1.2.0")
        val record = assertNotNull(parser.parse(normal))
        assertEquals("t-9", record.traceId)
        assertEquals("1.2.0", record.appVersion)
        assertEquals("Order failed", record.message)
    }

    @Test
    fun `a client error without exception detail has no exception text`() {
        val record = assertNotNull(ClientErrorLogParser(berlin).parse(LogFixtures.clientErrorEntry("2026-10-09 08:11:00.000", message = "m")))
        assertNull(record.exceptionText)
    }
}
