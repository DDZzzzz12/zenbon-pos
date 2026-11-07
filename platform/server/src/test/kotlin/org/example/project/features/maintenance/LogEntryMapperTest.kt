package org.example.project.features.maintenance

import org.example.project.features.maintenance.data.logs.LogEntryMapper
import org.example.project.features.maintenance.data.logs.LogLevel
import org.example.project.features.maintenance.data.logs.LogRecord
import org.example.project.features.maintenance.data.logs.LogSourceKind
import org.example.project.features.maintenance.data.logs.PathNormalizer
import org.example.project.features.maintenance.domain.dto.MaintenanceDto
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogEntryMapperTest {
    private val normalizer = PathNormalizer(setOf("api", "employees"))

    private fun record(
        message: String = "Employee 1042 does not have permission",
        logger: String? = "rejected",
        path: String? = "/api/employees/42",
        exceptionText: String? = null,
    ) = LogRecord(
        source = LogSourceKind.REJECTED,
        timestamp = Instant.parse("2026-10-09T06:00:00Z"),
        level = LogLevel.INFO,
        logger = logger,
        traceId = "trace-1",
        code = "AUTH_FORBIDDEN",
        statusCode = 403,
        method = "GET",
        path = path,
        durationMs = 12,
        message = message,
        exceptionText = exceptionText,
        clientApp = null,
        clientErrorId = null,
        appVersion = null,
    )

    @Test
    fun `the message is scrubbed and the path is normalised`() {
        val dto = LogEntryMapper.toDto(record(), normalizer)
        assertFalse("1042" in dto.message)
        assertEquals("/api/employees/{id}", dto.path)
        assertEquals("2026-10-09T06:00:00Z", dto.timestamp)
        assertEquals("trace-1", dto.traceId)
        assertEquals(403, dto.statusCode)
    }

    @Test
    fun `stack frames are kept verbatim and the exception message is scrubbed`() {
        val dto =
            LogEntryMapper.toDto(
                record(
                    exceptionText =
                        "java.lang.IllegalStateException: Voucher SUMMER-24 gone\n" +
                            "\tat org.example.Foo.bar(Foo.kt:12)\n" +
                            "\t... 3 more",
                ),
                normalizer,
            )
        val exception = assertNotNull(dto.exception)
        assertEquals(listOf("at org.example.Foo.bar(Foo.kt:12)", "... 3 more"), exception.frames)
        assertTrue("24" !in exception.summary, exception.summary)
        assertTrue("IllegalStateException" in exception.summary)
    }

    @Test
    fun `an identity logger's exception summary is withheld, its frames are kept`() {
        val dto =
            LogEntryMapper.toDto(
                record(
                    logger = "AuthRoutes",
                    message = "Login attempt for identifier: anna.mueller",
                    exceptionText = "java.lang.IllegalStateException: no employee anna.mueller\n\tat org.example.Auth.login(Auth.kt:9)",
                ),
                normalizer,
            )
        assertEquals("<redacted: identity log>", dto.message)
        val exception = assertNotNull(dto.exception)
        assertEquals("<redacted: identity log>", exception.summary)
        assertEquals(listOf("at org.example.Auth.login(Auth.kt:9)"), exception.frames)
    }

    @Test
    fun `client-supplied frame text is scrubbed of quoted text, emails and uuids`() {
        val dto =
            LogEntryMapper.toDto(
                record(
                    exceptionText =
                        "Error: x\n" +
                            "    at fn ('Anna Mueller' anna@example.com 123e4567-e89b-12d3-a456-426614174000:1:2)",
                ),
                normalizer,
            )
        val frames = assertNotNull(dto.exception).frames.joinToString("\n")
        listOf("Anna", "anna@", "123e4567").forEach { secret -> assertFalse(secret in frames, "'$secret' leaked: $frames") }
    }

    @Test
    fun `frames are capped at 200 per entry`() {
        val text = (listOf("java.lang.StackOverflowError") + List(500) { "\tat a.B.c(B.kt:$it)" }).joinToString("\n")
        assertEquals(200, assertNotNull(LogEntryMapper.toDto(record(exceptionText = text), normalizer).exception).frames.size)
    }

    @Test
    fun `a huge message and a huge exception summary are cut before scrubbing`() {
        val dto =
            LogEntryMapper.toDto(
                record(message = "x".repeat(100_000), exceptionText = "java.lang.IllegalStateException: " + "y".repeat(100_000)),
                normalizer,
            )
        assertTrue(dto.message.endsWith("…[truncated]"), dto.message.takeLast(40))
        assertTrue(dto.message.length <= 4_000 + "…[truncated]".length, "length ${dto.message.length}")
        val summary = assertNotNull(dto.exception).summary
        assertTrue(summary.endsWith("…[truncated]"), summary.takeLast(40))
        assertTrue(summary.length <= 4_000 + "…[truncated]".length, "length ${summary.length}")
    }

    @Test
    fun `no exception text means no exception object`() {
        assertNull(LogEntryMapper.toDto(record(exceptionText = null), normalizer).exception)
        assertNull(LogEntryMapper.toDto(record(exceptionText = "  \n "), normalizer).exception)
    }

    @Test
    fun `the DTO type has no field that could carry a person's identity`() {
        // A guard on the TYPE: adding such a field later must be a deliberate, reviewed act.
        val forbidden = setOf("employeeId", "deviceId", "username", "employeeNumber", "reportedTerminalId", "screen")
        val fields =
            MaintenanceDto.LogEntryDto::class.java.declaredFields
                .map { it.name }
                .toSet()
        assertTrue(fields.intersect(forbidden).isEmpty(), "identity-bearing fields: ${fields.intersect(forbidden)}")
    }
}
