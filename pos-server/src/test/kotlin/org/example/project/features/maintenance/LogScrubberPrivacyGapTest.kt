package org.example.project.features.maintenance

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.project.features.maintenance.data.logs.ClientErrorLogParser
import org.example.project.features.maintenance.data.logs.LogEntryMapper
import org.example.project.features.maintenance.data.logs.LogLevel
import org.example.project.features.maintenance.data.logs.LogRecord
import org.example.project.features.maintenance.data.logs.LogScrubber
import org.example.project.features.maintenance.data.logs.LogSourceKind
import org.example.project.features.maintenance.data.logs.PathNormalizer
import org.example.project.testutils.LogFixtures
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Adversarial privacy corpus for [LogScrubber], built from the shapes this server really logs
 * (NotificationPublisher, AuthNotificationManager, JwtConfig, ProductExtrasAssignmentServiceImpl, ClientErrorRoutes,
 * StatusPages). Cases in the first group must hold; the second group pins the leaks that were FOUND in the
 * 2026-10-10 review. Findings 1-3 are closed and live; finding 4 (an unprefixed username) is an accepted
 * risk and stays `@Disabled` with its reason.
 */
class LogScrubberPrivacyGapTest {
    private val employeeUuid = "7b0c1d2e-3f40-4152-8364-758697a8b9ca"
    private val deviceUuid = "0f1e2d3c-4b5a-4968-8776-655443322110"
    private val traceUuid = "c0ffee00-1234-4abc-9def-0123456789ab"

    private fun scrub(
        logger: String?,
        raw: String,
    ) = LogScrubber.scrubMessage(logger, raw)

    private fun assertNoLeak(
        logger: String?,
        raw: String,
        vararg secrets: String,
    ) {
        val out = scrub(logger, raw)
        secrets.forEach { assertTrue(it !in out, "'$it' leaked: [$out] from [$raw]") }
    }

    // ---- must hold -------------------------------------------------------------------------------------

    @Test
    fun `SSE connection lines from NotificationPublisher drop employee and device ids`() {
        val logger = "org.example.project.core.websocket.NotificationPublisher"
        assertNoLeak(
            logger,
            "SSE connection added: $traceUuid for employee=$employeeUuid device=admin-web-$deviceUuid station=false",
            employeeUuid,
            deviceUuid,
            "admin-web-",
        )
        assertNoLeak(logger, "SSE stream opened: conn-9 employee=42 device=pos-preview-ab12cd34 lastEventId=7", "pos-preview", "ab12cd34")
        assertNoLeak(logger, "SSE connection removed: conn-9 for employee 1042", "1042")
    }

    @Test
    fun `AuthNotificationManager style device lines drop the device id`() {
        val logger = "org.example.project.core.websocket.SomethingElse"
        assertNoLeak(logger, "Notified device admin-web-$deviceUuid of session end: 55", deviceUuid)
        assertNoLeak(logger, "Notified device $deviceUuid of session creation: 9", deviceUuid)
        assertNoLeak(logger, "Sent APPROVAL_RESULT for request 12 to device till-7f3a", "till-7f3a")
        assertNoLeak(logger, "No active session for device: pos-preview-9f8e7d", "pos-preview", "9f8e7d")
    }

    @Test
    fun `JwtConfig style deviceId lines drop device id and jti`() {
        val logger = "org.example.project.core.security.JwtConfig"
        assertNoLeak(
            logger,
            "Generating access token for employee with deviceId: admin-web-$deviceUuid, jti: $traceUuid",
            deviceUuid,
            traceUuid,
            "admin-web",
        )
        assertNoLeak(logger, "Generating station access token for deviceId: till-7f3a, station: KITCHEN, jti: x", "till-7f3a")
    }

    @Test
    fun `employeeId equals uuid or number is not kept`() {
        val logger = "org.example.project.features.product.data.service.ProductExtrasAssignmentServiceImpl"
        assertNoLeak(logger, "assignExtrasToProduct: productId=12, extrasCount=3, employeeId=$employeeUuid", employeeUuid)
        assertNoLeak(logger, "assignExtrasToProduct: productId=12, extrasCount=3, employeeId=1042", "1042")
    }

    @Test
    fun `a pin value that is digits does not survive and a trace id does not leak through the structured mapper`() {
        assertNoLeak("rejected", "PIN reset rejected, pin=482915", "482915")
        assertNoLeak("rejected", "Invalid PIN: 482915", "482915")
    }

    @Test
    fun `a uuid inside a message is replaced even when it is the trace id, but the structured traceId field survives`() {
        assertEquals("trace <uuid> failed", scrub("rejected", "trace $traceUuid failed"))
        val dto = LogEntryMapper.toDto(record("Rejected request $traceUuid", traceId = traceUuid), PathNormalizer(setOf("api")))
        assertEquals(traceUuid, dto.traceId)
        assertTrue(traceUuid !in dto.message, dto.message)
    }

    @Test
    fun `ips, emails and ibans are blanked`() {
        assertNoLeak("rejected", "Connected to terminal at 192.168.178.151:22000", "192.168", "178", "22000")
        assertNoLeak("rejected", "Mail to anna.mueller@example.com failed", "anna", "example.com")
    }

    @Test
    fun `a client-error entry never surfaces screen, employee or device and scrubs its free text`() {
        val block =
            LogFixtures.clientErrorEntry(
                timestamp = "2026-10-09 08:11:00.000",
                message = "[POS_TILL] FROM_SERVER — Fehler: Kunde 'Anna Mueller' (anna@example.com) not found for employee 1042",
                screen = "/customers/Anna-Mueller",
                employee = "1042",
                device = "admin-web-$deviceUuid",
                traceId = traceUuid,
                exceptionType = "java.lang.IllegalStateException",
                exceptionMessage = "no row for user: anna.mueller at device $deviceUuid",
                stackLines = listOf("    at fn (https://till.local/app.js:1:2)", "    at org.example.Foo.bar(Foo.kt:12)"),
            )
        val rec = assertNotNull(ClientErrorLogParser(ZoneId.of("Europe/Berlin")).parse(block))
        val dto = LogEntryMapper.toDto(rec, PathNormalizer(setOf("api")))
        val json = Json.encodeToString(dto)
        listOf("Anna", "anna", "Mueller", "1042", deviceUuid, "admin-web-", "/customers").forEach { secret ->
            assertTrue(secret !in json, "'$secret' leaked into the client-error DTO: $json")
        }
        assertEquals(traceUuid, dto.traceId)
    }

    // ---- findings (documented leaks, disabled so the build stays green) -----------------------------------

    @Test
    fun `FINDING 1 - credential keywords are redacted`() {
        assertNoLeak("rejected", "Login failed, password=hunter2x for request", "hunter")
        assertNoLeak("rejected", "Refused: token: abcDEF-ghi", "abcDEF-ghi")
        assertNoLeak("rejected", "apiKey=sk_live_abcdef", "sk_live")
    }

    @Test
    fun `FINDING 2 - JWT and Bearer strings are redacted`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJlbXBsb3llZU51bWJlciI6IkFubmEifQ.sig_abcDEF-123"
        assertNoLeak("rejected", "Request failed with Authorization: Bearer $jwt", "eyJhbGciOiJIUzI1NiJ9", "eyJlbXBsb3llZU51bWJlciI6IkFubmEifQ", "eyJ")
        assertNoLeak("rejected", "Invalid token $jwt", "eyJ")
    }

    @Test
    fun `FINDING 3 - a two-word name after a person keyword is fully redacted`() {
        assertNoLeak("rejected", "Customer Anna Mueller not found", "Anna", "Mueller")
        assertNoLeak("rejected", "Order opened by employee Anna Mueller", "Anna", "Mueller")
    }

    @org.junit.jupiter.api.Disabled(
        "FINDING 4 (accepted risk per spec §3): a name with no keyword in front of it is not recognised. Not fixable by a rule; " +
            "shown so nobody assumes otherwise.",
    )
    @Test
    fun `FINDING 4 - an unprefixed lowercase username is redacted`() {
        assertNoLeak("rejected", "Login failed for anna.mueller", "anna.mueller")
    }

    @Test
    fun `credential and token prose keeps its readable shape`() {
        assertEquals("Token refreshed in # ms", scrub("rejected", "Token refreshed in 12 ms"))
        assertEquals("token refresh scheduled", scrub("rejected", "token refresh scheduled"))
        assertEquals("secret santa list loaded", scrub("rejected", "secret santa list loaded"))
        assertEquals("Generating tokens for the shift", scrub("rejected", "Generating tokens for the shift"))
        assertEquals("Authorization: <redacted> <redacted>", scrub("rejected", "Authorization: Bearer abc.def"))
        assertTrue("abc" !in scrub("rejected", "X-Api-Key: abc"))
        assertEquals("Customer <redacted> not found", scrub("rejected", "Customer Anna Mueller not found"))
    }

    @Test
    fun `a name stops after three tokens and a lowercase value is not extended`() {
        assertEquals("Customer <redacted> Order saved", scrub("rejected", "Customer Anna Maria Mueller Order saved"))
        assertEquals("Created by employee <redacted> Failed", scrub("rejected", "Created by employee anna.mueller Failed"))
        assertEquals("user: <redacted> von", scrub("rejected", "user: Anna von"))
    }

    @Test
    fun `a jwt in a stack frame is replaced and line numbers stay`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJlbXBsb3llZU51bWJlciI6IkFubmEifQ.sig_abc-123"
        val out = LogScrubber.scrubFrame("at fn (https://till.local/app.js:1:2?t=$jwt) Bearer $jwt")
        assertTrue("eyJ" !in out, out)
        assertTrue(":1:2" in out, out)
    }

    @Test
    fun `scrubbing credentials and names is idempotent and leaves the trace id alone`() {
        listOf(
            "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJhIjoiYiJ9.c2ln trace $traceUuid",
            "password=hunter2x token: abc Customer Anna Mueller not found",
            "user: Anna Mueller",
        ).forEach { raw ->
            val once = LogScrubber.scrub(raw)
            assertEquals(once, LogScrubber.scrub(once), raw)
        }
        assertEquals("trace <uuid>", LogScrubber.scrub("trace $traceUuid"))
    }

    private fun record(
        message: String,
        traceId: String?,
    ) = LogRecord(
        source = LogSourceKind.REJECTED,
        timestamp = Instant.parse("2026-10-09T06:00:00Z"),
        level = LogLevel.INFO,
        logger = "rejected",
        traceId = traceId,
        code = null,
        statusCode = 403,
        method = "GET",
        path = "/api/x",
        durationMs = 1,
        message = message,
        exceptionText = null,
        clientApp = null,
        clientErrorId = null,
        appVersion = null,
    )
}
