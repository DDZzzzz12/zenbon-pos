package org.example.project.routes

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.security.JwtConfig
import org.example.project.features.auth.data.TemporarySessionStore
import org.example.project.testutils.TestData
import org.example.project.testutils.assertStatus
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentInstant
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

/**
 * Order-station shell: a JWT carrying `station=ORDER_STATION` is masked exactly like the device
 * token that minted it; the same employee's JWT without the claim is not; and a step-up still
 * lends past the mask (identity → mask → union).
 */
@OptIn(ExperimentalTime::class)
@DisplayName("Station claim masking")
class StationClaimMaskingTest {
    private val deviceId = "STATION-CLAIM-X"
    private val sessionId = "SESSION-station-claim-test"

    @AfterEach
    fun removeSession() {
        TemporarySessionStore.removeSession(sessionId)
    }

    /** Seeds a fiscal, TSE-registered, active ORDER_STATION bound to [deviceId]. */
    private fun seedStation() {
        TestData.createFiscalRegisteredTerminal(
            deviceId = deviceId,
            clientId = "TER-STATION-CLAIM",
            role = "ORDER_STATION",
        )
    }

    /** (employeeId, employeeNumber) of the shared terminal-employee. Idempotent — never a second row. */
    private fun terminalEmployee(): Pair<String, String> =
        transaction {
            val employee = TestData.createTerminalDeviceEmployee()
            employee.id.value.toString() to employee.employeeNumber
        }

    private fun stationToken(): String {
        val (id, number) = terminalEmployee()
        return JwtConfig.generateStationAccessToken(
            employeeId = id,
            employeeNumber = number,
            deviceId = deviceId,
            station = "ORDER_STATION",
        )
    }

    private fun personToken(): String {
        val (id, number) = terminalEmployee()
        return JwtConfig.generateAccessToken(
            employeeId = id,
            employeeNumber = number,
            role = EmployeeRole.TERMINAL_DEVICE.name,
            deviceId = deviceId,
        )
    }

    @Test
    fun `a station token cannot read bills but can read reservations`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createTerminalDeviceEmployee()
                seedStation()
            },
        ) { client ->
            val token = stationToken()

            // BILL_READ is in the TERMINAL_DEVICE group and NOT in the order-station mask.
            assertStatus(HttpStatusCode.Forbidden) {
                client.get("/api/v1/bills") { bearerAuth(token) }
            }

            val reservations = client.get("/api/reservations") { bearerAuth(token) }
            assertNotEquals(HttpStatusCode.Forbidden, reservations.status, "RESERVATION_READ is in the station mask")
            assertNotEquals(HttpStatusCode.Unauthorized, reservations.status, "the station JWT must authenticate")
        }

    @Test
    fun `a station token cannot create a bill either`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createTerminalDeviceEmployee()
                seedStation()
            },
        ) { client ->
            val token = stationToken()

            // BILL_WRITE, like BILL_READ, is not in the order-station mask (station shell design,
            // 2026-09-06: "No BILL_*: billing does not happen at an order station at all"). The
            // capability ceiling runs before body parsing, so an intentionally-empty/invalid body
            // still proves authorization — not validation — is what refuses the request.
            assertStatus(HttpStatusCode.Forbidden) {
                client.post("/api/v1/bills") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            }
        }

    @Test
    fun `the same employee without the claim is not masked`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createTerminalDeviceEmployee()
                seedStation()
            },
        ) { client ->
            val bills = client.get("/api/v1/bills") { bearerAuth(personToken()) }
            assertNotEquals(HttpStatusCode.Forbidden, bills.status, "a JWT without the claim passes as UNBOUND")
        }

    @Test
    fun `a step-up lends past the mask`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createTerminalDeviceEmployee()
                seedStation()
            },
        ) { client ->
            val token = stationToken()
            val (employeeId, _) = terminalEmployee()

            // TABLE_SESSION_OVERRIDE is outside both the group and the mask.
            assertStatus(HttpStatusCode.Forbidden) {
                client.delete("/api/tables/999999/session?reason=Test") {
                    bearerAuth(token)
                    header("X-Device-ID", deviceId)
                }
            }

            val manager = TestData.createManager()
            TemporarySessionStore.addSession(
                TemporarySessionStore.SessionInfo(
                    sessionId = sessionId,
                    managerId = transaction { manager.id.value.toString() },
                    managerName = "Test Manager",
                    managerRole = EmployeeRole.MANAGER,
                    deviceId = deviceId,
                    requestingEmployeeId = employeeId,
                    grantedCapabilities = setOf(Capability.TABLE_SESSION_OVERRIDE),
                    createdAt = currentInstant(),
                    expiresAt = currentInstant().plus(5.minutes),
                ),
            )

            // Same token, same station — the lent capability is unioned AFTER the mask, so the
            // refusal is no longer an authorization one (the table does not exist: 404).
            val afterElevation =
                client.delete("/api/tables/999999/session?reason=Test") {
                    bearerAuth(token)
                    header("X-Device-ID", deviceId)
                }
            assertNotEquals(HttpStatusCode.Forbidden, afterElevation.status)
        }
}
