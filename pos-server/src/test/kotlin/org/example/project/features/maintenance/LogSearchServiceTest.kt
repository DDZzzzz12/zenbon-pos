package org.example.project.features.maintenance

import org.example.project.core.error.PosErrorCode
import org.example.project.features.maintenance.data.logs.LogLevel
import org.example.project.features.maintenance.data.logs.LogQuery
import org.example.project.features.maintenance.data.logs.LogSourceKind
import org.example.project.features.maintenance.data.logs.PathNormalizer
import org.example.project.features.maintenance.data.service.LogSearchService
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.LogFixtures
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogSearchServiceTest {
    @TempDir
    lateinit var dir: File

    private val berlin = ZoneId.of("Europe/Berlin")
    private val normalizer = PathNormalizer(setOf("api", "employees", "products"))

    private fun service(
        matchCap: Int = 1_000,
        maxChars: Long = 256L * 1024 * 1024,
        nanoTime: () -> Long = System::nanoTime,
        maxNanos: Long = 8_000_000_000L,
    ) = LogSearchService(
        logDir = { dir },
        normalizer = normalizer,
        matchCap = matchCap,
        maxScanChars = maxChars,
        maxScanNanos = maxNanos,
        nanoTime = nanoTime,
        zone = berlin,
    )

    private fun write(
        name: String,
        lines: List<String>,
    ) = File(dir, name).writeText(lines.joinToString("\n") + "\n")

    private fun writeGz(
        name: String,
        lines: List<String>,
    ) = GZIPOutputStream(File(dir, name).outputStream()).bufferedWriter().use { it.write(lines.joinToString("\n") + "\n") }

    @BeforeEach
    fun fixtures() {
        write(
            "rejected.log",
            listOf(
                LogFixtures.jsonLine(
                    "2026-10-09T08:00:00+02:00",
                    "AUTH_FORBIDDEN 403 — Employee 1042 does not have permission",
                    traceId = "t-1",
                    code = "AUTH_FORBIDDEN",
                    status = 403,
                    method = "GET",
                    path = "/api/employees/42",
                    employeeId = "emp-secret-123",
                    deviceId = "dev-secret-9",
                ),
                LogFixtures.jsonLine(
                    "2026-10-09T08:05:00+02:00",
                    "VALIDATION_FAILED 400 — bad value",
                    traceId = "t-2",
                    code = "VALIDATION_FAILED",
                    status = 400,
                    method = "POST",
                    path = "/api/products",
                ),
            ),
        )
        write(
            "error.log",
            LogFixtures.errorLogEntry(
                "2026-10-09 08:10:00.000",
                "t-3",
                "INTERNAL_ERROR",
                "500",
                "StatusPages",
                "Request failed: boom",
                listOf("java.lang.IllegalStateException: boom", "\tat org.example.Foo.bar(Foo.kt:12)"),
            ),
        )
        write(
            "client-error.log",
            LogFixtures.clientErrorEntry("2026-10-09 08:11:00.000", "Order failed", clientErrorId = "c-1", traceId = "t-1", httpStatus = "403"),
        )
        write("application.2026-10-08.log", listOf(LogFixtures.jsonLine("2026-10-08T23:59:00+02:00", "Order saved", traceId = "t-0", logger = "Orders")))
        writeGz(
            "error.2026-10-08.0.log.gz",
            LogFixtures.errorLogEntry("2026-10-08 12:00:00.000", "t-g", "INTERNAL_ERROR", "500", "StatusPages", "old failure"),
        )
    }

    @Test
    fun `one trace id is followed across sources, newest first`() {
        val result = service().search(LogQuery(traceId = "t-1"))
        assertEquals(listOf("client-error", "rejected"), result.entries.map { it.source })
        assertEquals(2, result.matched)
        assertNull(result.truncated)
    }

    @Test
    fun `a gzipped rotated file is read`() {
        val result = service().search(LogQuery(traceId = "t-g"))
        assertEquals(listOf("old failure"), result.entries.map { it.message })
    }

    @Test
    fun `a rotated file outside the range is not even opened`() {
        val from = LocalDate.parse("2026-10-09").atStartOfDay(berlin).toInstant()
        val result = service().search(LogQuery(from = from))
        assertEquals(3, result.scannedFiles, "rejected.log, error.log, client-error.log only")
        assertFalse(result.entries.any { it.traceId == "t-0" || it.traceId == "t-g" })
    }

    @Test
    fun `level is a minimum`() {
        val errors = service().search(LogQuery(minLevel = LogLevel.ERROR, from = null))
        assertTrue(errors.entries.all { it.level == "ERROR" })
        assertTrue(errors.entries.none { it.traceId == "t-0" })
    }

    @Test
    fun `free text matches the scrubbed text only`() {
        assertEquals(0, service().search(LogQuery(text = "1042", traceId = "t-1")).entries.size)
        assertEquals(1, service().search(LogQuery(text = "does not have permission", traceId = "t-1")).entries.size)
    }

    @Test
    fun `nothing that identifies a person is returned`() {
        val json =
            kotlinx.serialization.json.Json.encodeToString(
                org.example.project.features.maintenance.domain.dto.MaintenanceDto.LogSearchResponseDto
                    .serializer(),
                service().search(LogQuery(traceId = "t-1")),
            )
        listOf("emp-secret-123", "dev-secret-9", "1042", "employeeId", "deviceId").forEach { secret ->
            assertFalse(secret in json, "'$secret' is in the response: $json")
        }
        assertTrue("/api/employees/{id}" in json, json)
    }

    @Test
    fun `the source filter limits which files are read`() {
        val result = service().search(LogQuery(traceId = "t-1", sources = setOf(LogSourceKind.REJECTED)))
        assertEquals(listOf("rejected"), result.entries.map { it.source })
    }

    @Test
    fun `the oldest rotated date is reported so the page can explain a short retention`() {
        assertEquals("2026-10-08", service().search(LogQuery(traceId = "t-1")).oldestAvailable)
    }

    @Test
    fun `a corrupt line is skipped, not fatal`() {
        File(dir, "rejected.log").appendText("{ this is not json\n")
        assertEquals(2, service().search(LogQuery(sources = setOf(LogSourceKind.REJECTED), from = null)).entries.size)
    }

    @Test
    fun `too many matches are cut to the newest cap and say so`() {
        write(
            "rejected.log",
            (1..5).map { i ->
                LogFixtures.jsonLine("2026-10-09T08:0$i:00+02:00", "VALIDATION_FAILED 400 — n$i", traceId = "many", code = "VALIDATION_FAILED", status = 400)
            },
        )
        val result = service(matchCap = 3).search(LogQuery(traceId = "many", sources = setOf(LogSourceKind.REJECTED)))
        assertEquals("MATCH_CAP", result.truncated)
        assertEquals(3, result.entries.size)
        // The scrubber turns every digit run into '#', the status code included.
        assertEquals(List(3) { "VALIDATION_FAILED # — n#" }, result.entries.map { it.message })
        assertEquals(Instant.parse("2026-10-09T06:05:00Z").toString(), result.entries.first().timestamp, "the newest entry survives")
    }

    @Test
    fun `a spent character budget stops the scan and says so`() {
        val result = service(maxChars = 10).search(LogQuery(traceId = "t-1"))
        assertEquals("SCAN_BUDGET", result.truncated)
    }

    @Test
    fun `a spent time budget stops the scan and says so`() {
        var tick = 0L
        val result =
            service(nanoTime = {
                tick += 5_000_000_000L
                tick
            }, maxNanos = 8_000_000_000L).search(LogQuery(traceId = "t-1"))
        assertEquals("SCAN_BUDGET", result.truncated)
    }

    @Test
    fun `limit cuts the list but not the matched count`() {
        val result = service().search(LogQuery(from = null, limit = 1))
        assertEquals(1, result.entries.size)
        assertTrue(result.matched > 1)
    }

    @Test
    fun `current files of every source are read before any rotated file`() {
        // ~7 x 300 lines of ~250 characters of rotated rejected.log; the budget covers well under half.
        (1..7).forEach { day ->
            writeGz(
                "rejected.2026-10-0$day.0.log.gz",
                (1..300).map { i ->
                    LogFixtures.jsonLine("2026-10-0${day}T08:00:00+02:00", "VALIDATION_FAILED 400 — filler $i ".padEnd(150, 'x'), traceId = "f-$i")
                },
            )
        }
        val result = service(maxChars = 150_000).search(LogQuery(traceId = "t-3"))
        assertEquals(listOf("error"), result.entries.map { it.source }, "error.log must be searched before old rejected files")
        assertEquals("SCAN_BUDGET", result.truncated)
    }

    @Test
    fun `a trace id the line prefilter cannot use is still found`() {
        // JSON escapes a quote, so the raw line does not contain the trace id verbatim.
        File(dir, "rejected.log").appendText(
            LogFixtures.jsonLine("2026-10-09T08:06:00+02:00", "odd", traceId = "a\"b/c", code = "VALIDATION_FAILED", status = 400) + "\n",
        )
        assertEquals(listOf("odd"), service().search(LogQuery(traceId = "a\"b/c")).entries.map { it.message })
    }

    @Test
    fun `the trace id prefilter drops a non-matching entry together with its stack lines`() {
        write(
            "error.log",
            LogFixtures.errorLogEntry(
                "2026-10-09 08:10:00.000",
                "t-other",
                "INTERNAL_ERROR",
                "500",
                "StatusPages",
                "other",
                listOf("\tat t3.Mentions.t-3(X.kt:1)"),
            ) +
                LogFixtures.errorLogEntry("2026-10-09 08:11:00.000", "t-3", "INTERNAL_ERROR", "500", "StatusPages", "mine", listOf("\tat a.B.c(B.kt:1)")),
        )
        val entries = service().search(LogQuery(traceId = "t-3", sources = setOf(LogSourceKind.ERROR))).entries
        assertEquals(listOf("mine"), entries.map { it.message })
        assertEquals(listOf("at a.B.c(B.kt:1)"), entries.single().exception?.frames)
    }

    @Test
    fun `a single 5 MB line is cut, charged to the budget, and does not hide the normal lines`() {
        val huge = LogFixtures.jsonLine("2026-10-09T08:07:00+02:00", "a".repeat(5 * 1024 * 1024), traceId = "t-1")
        File(dir, "rejected.log").appendText(huge + "\n" + LogFixtures.jsonLine("2026-10-09T08:08:00+02:00", "after", traceId = "t-1") + "\n")
        val result =
            assertTimeoutPreemptively(
                Duration.ofSeconds(10),
                ThrowingSupplier { service().search(LogQuery(traceId = "t-1", sources = setOf(LogSourceKind.REJECTED))) },
            )
        assertEquals(listOf("after", "AUTH_FORBIDDEN # — Employee <redacted> does not have permission"), result.entries.map { it.message })
        assertEquals("SCAN_BUDGET", service(maxChars = 1_000_000).search(LogQuery(traceId = "t-1", sources = setOf(LogSourceKind.REJECTED))).truncated)
    }

    @Test
    fun `the effective lower time bound is reported`() {
        val from = Instant.parse("2026-10-09T05:00:00Z")
        assertEquals("2026-10-09T05:00:00Z", service().search(LogQuery(from = from)).from)
        assertNull(service().search(LogQuery(traceId = "t-1")).from)
    }

    @Test
    fun `an unreadable log directory is a 503`() {
        val missing = LogSearchService(logDir = { File(dir, "does-not-exist") }, normalizer = normalizer)
        val failure = assertFailsWith<PosException> { missing.search(LogQuery()) }
        assertEquals(PosErrorCode.MAINTENANCE_LOG_DIR_UNAVAILABLE, failure.code)
        assertEquals(503, failure.httpStatus.value)
    }
}
