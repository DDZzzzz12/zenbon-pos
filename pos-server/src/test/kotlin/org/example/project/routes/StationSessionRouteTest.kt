package org.example.project.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.security.JwtConfig
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.terminal.data.service.PairAttemptLimiter
import org.example.project.features.terminal.database.tables.TerminalTable
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.domain.session.TseAdminSession
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentInstant
import org.example.project.utils.formatter.getTimeZone
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime

/**
 * The station-session exchange: a paired ORDER_STATION trades its device token for a masked
 * station JWT. Every refusal the design names, in order of the pipeline that produces it: the
 * transactional ceiling (409 / 403 / 424), the role check (403), the missing terminal-employee (500).
 */
@OptIn(ExperimentalTime::class)
@DisplayName("POST /api/v1/terminals/session")
class StationSessionRouteTest {
    private data class Paired(
        val dbId: Long,
        val clientId: String,
        val deviceToken: String,
    )

    @BeforeEach
    fun resetPairingState() {
        PairAttemptLimiter.resetAllForTests()
        TseAdminSession.clear()
    }

    /** Provisions a terminal with [role] and pairs [deviceId] to it through the real HTTP flow. */
    private suspend fun pair(
        client: HttpClient,
        adminToken: String,
        deviceId: String,
        role: String,
    ): Paired {
        val created =
            client
                .post("/api/v1/terminals") {
                    bearerAuth(adminToken)
                    setBody(TextContent("""{"name":"Station $deviceId","role":"$role"}""", ContentType.Application.Json))
                }.let { testJson.decodeFromString<TerminalDto.TerminalResponseDto>(it.bodyAsText()) }
        val code =
            client
                .post("/api/v1/terminals/${created.id}/pairing-code") { bearerAuth(adminToken) }
                .let { testJson.decodeFromString<TerminalDto.PairingCodeResponseDto>(it.bodyAsText()) }
                .code
        val paired =
            client
                .post("/api/v1/terminals/pair") {
                    setBody(TextContent("""{"code":"$code","deviceId":"$deviceId"}""", ContentType.Application.Json))
                }.let { testJson.decodeFromString<TerminalDto.PairDeviceResponseDto>(it.bodyAsText()) }
        return Paired(created.id, paired.terminal.terminalId, paired.deviceToken)
    }

    private suspend fun openSession(
        client: HttpClient,
        deviceToken: String,
    ): HttpResponse =
        client.post("/api/v1/terminals/session") {
            header("X-Device-Token", deviceToken)
        }

    @Test
    fun `a paired fiscal order station receives a masked station JWT`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createTerminalDeviceEmployee()
                TestData.setTseMode(TseMode.SIMULATION)
            },
        ) { client ->
            val admin = TestAuthHelper.loginAsAdmin(client)
            val station = pair(client, admin, "station-session-ok", "ORDER_STATION")

            val response = openSession(client, station.deviceToken)
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())

            val dto = testJson.decodeFromString<TerminalDto.StationSessionResponseDto>(response.bodyAsText())
            assertEquals(station.clientId, dto.terminalId)
            assertEquals("ORDER_STATION", dto.role)
            assertTrue("ORDER_WRITE" in dto.capabilities)
            assertTrue("RESERVATION_WRITE" in dto.capabilities)
            assertTrue("TERMINAL_READ" in dto.capabilities)
            assertFalse("BILL_READ" in dto.capabilities, "the group holds BILL_READ; the mask must have removed it")
            assertFalse("RESERVATION_DELETE" in dto.capabilities)

            val claims = assertNotNull(JwtConfig.validateToken(dto.accessToken))
            assertEquals("ORDER_STATION", claims.station)
            assertEquals(EmployeeRole.TERMINAL_DEVICE.name, claims.role)
            assertEquals("station-session-ok", claims.deviceId, "the PHYSICAL device id, not the TER- client id")
            val terminalEmployeeId =
                transaction {
                    Employee
                        .find { EmployeeTable.username eq "terminal-device" }
                        .first()
                        .id.value
                        .toString()
                }
            assertEquals(terminalEmployeeId, claims.employeeId)
            assertEquals(assertNotNull(claims.expiresAt).toString(), dto.expiresAt)
        }

    @Test
    fun `an unknown device token is refused as unpaired with 409`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createTerminalDeviceEmployee()
                TestData.setTseMode(TseMode.SIMULATION)
            },
        ) { client ->
            assertEquals(HttpStatusCode.Conflict, openSession(client, "totally-bogus-token").status)
        }

    @Test
    fun `a deactivated station is refused with 403`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createTerminalDeviceEmployee()
                TestData.setTseMode(TseMode.SIMULATION)
            },
        ) { client ->
            val admin = TestAuthHelper.loginAsAdmin(client)
            val station = pair(client, admin, "station-session-deactivated", "ORDER_STATION")
            client.delete("/api/v1/terminals/${station.dbId}") { bearerAuth(admin) }

            assertEquals(HttpStatusCode.Forbidden, openSession(client, station.deviceToken).status)
        }

    @Test
    fun `a CASHIER terminal has no station shell and is refused with 403`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createTerminalDeviceEmployee()
                TestData.setTseMode(TseMode.SIMULATION)
            },
        ) { client ->
            val admin = TestAuthHelper.loginAsAdmin(client)
            val till = pair(client, admin, "station-session-cashier", "CASHIER")

            val response = openSession(client, till.deviceToken)
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("no shell for role CASHIER"), response.bodyAsText())
        }

    @Test
    fun `a KITCHEN terminal is refused with 403`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createTerminalDeviceEmployee()
                TestData.setTseMode(TseMode.SIMULATION)
            },
        ) { client ->
            val admin = TestAuthHelper.loginAsAdmin(client)
            val kitchen = pair(client, admin, "station-session-kitchen", "KITCHEN")

            // KITCHEN is non-fiscal, so the ceiling refuses it before the role check even runs.
            assertEquals(HttpStatusCode.Forbidden, openSession(client, kitchen.deviceToken).status)
        }

    @Test
    fun `an unregistered station in NORMAL TSE mode is refused with 424`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createTerminalDeviceEmployee()
                TestData.setTseMode(TseMode.NORMAL)
            },
        ) { client ->
            val admin = TestAuthHelper.loginAsAdmin(client)
            val station = pair(client, admin, "station-session-unregistered", "ORDER_STATION")

            assertEquals(HttpStatusCode.FailedDependency, openSession(client, station.deviceToken).status)
        }

    @Test
    fun `a missing terminal-device employee is a broken install and answers 500`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                // Deleted rather than merely not created: bootstrap seeds this row on every start
                // now, so the absent-row case has to be produced deliberately. It is still reachable
                // in the field — someone can delete the employee — and must still be a 500 and not a
                // silently attributed order.
                TestData.removeTerminalDeviceEmployee()
                TestData.setTseMode(TseMode.SIMULATION)
            },
        ) { client ->
            val admin = TestAuthHelper.loginAsAdmin(client)
            val station = pair(client, admin, "station-session-no-employee", "ORDER_STATION")

            assertEquals(HttpStatusCode.InternalServerError, openSession(client, station.deviceToken).status)
        }

    /**
     * The guard against the two branches drifting apart.
     *
     * The exchange reports a capability set for the client to seed its gates from; the route plugin
     * resolves the set it enforces independently when the minted token comes back. Both must answer
     * the same question about the same principal, so this asserts EQUALITY against what the server
     * itself says it enforces - `GET /api/auth/me` responds with `heldCapabilities()`, the very set
     * the gate computed for that request - and then confirms the report is not merely an echo by
     * spending it: a capability the report omits is refused at a real route.
     *
     * Resolving the exchange's set by group code rather than by employee id passes this today and
     * fails it the moment the terminal-employee is moved out of the group named after their role,
     * which is precisely the silent drift the equality pins.
     */
    @Test
    fun `the reported capability set is the set the route plugin enforces`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createTerminalDeviceEmployee()
                TestData.setTseMode(TseMode.SIMULATION)
            },
        ) { client ->
            val admin = TestAuthHelper.loginAsAdmin(client)
            val station = pair(client, admin, "station-session-enforced", "ORDER_STATION")

            val dto =
                testJson.decodeFromString<TerminalDto.StationSessionResponseDto>(
                    openSession(client, station.deviceToken).bodyAsText(),
                )

            val me = client.get("/api/auth/me") { bearerAuth(dto.accessToken) }
            assertEquals(HttpStatusCode.OK, me.status, me.bodyAsText())
            val enforced = testJson.decodeFromString<EmployeeInfoDto.EmployeeInfo>(me.bodyAsText())
            assertEquals(
                enforced.capabilities,
                dto.capabilities,
                "the session exchange must report exactly what the route plugin resolves for this token",
            )

            // Not an echo: BILL_READ is absent from the report, and the gate really refuses it.
            assertFalse("BILL_READ" in dto.capabilities)
            assertEquals(
                HttpStatusCode.Forbidden,
                client.get("/api/v1/bills") { bearerAuth(dto.accessToken) }.status,
            )
        }

    /**
     * 401 is reachable only for an EXPIRED device token - a missing or unknown one is refused as
     * unpaired (409) by the terminal ceiling, before authentication runs. The route KDoc, which
     * generates the published API spec, says exactly that; this pins it.
     */
    @Test
    fun `an expired device token is rejected with 401`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createTerminalDeviceEmployee()
                TestData.setTseMode(TseMode.SIMULATION)
            },
        ) { client ->
            val admin = TestAuthHelper.loginAsAdmin(client)
            val station = pair(client, admin, "station-session-expired", "ORDER_STATION")

            transaction {
                Terminal
                    .find { TerminalTable.clientId eq station.clientId }
                    .first()
                    .deviceTokenExpiresAt = currentInstant().minus(1.days).toLocalDateTime(getTimeZone())
            }

            assertEquals(HttpStatusCode.Unauthorized, openSession(client, station.deviceToken).status)
        }
}
