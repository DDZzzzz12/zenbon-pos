package org.example.project.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import org.example.project.core.auth.ElevationScope
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.auth.data.TemporarySessionStore
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.terminal.data.service.PairAttemptLimiter
import org.example.project.features.tse.domain.session.TseAdminSession
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which station principals may open a manager sign-in, and which may not.
 *
 * History: NOTE-10 closed the step-up on a station entirely (the ORDER_STATION mask has no
 * `SELF_WRITE`, the route then required it). The owner decision of 2026-10-03 (emu21 NOTE-v)
 * reopened it for exactly one shape: a manager signing in temporarily on the station to perform a
 * Storno or a transfer. The route now admits `SELF_WRITE` OR `ORDER_WRITE` (the station's way in,
 * see `AuthRules`), still without granting the station `SELF_WRITE` itself — that stays pinned
 * here, because SELF_WRITE would also open the shared account's PIN and profile.
 *
 * What stays refused: the bare device token (the route plugin never elevates a device-token
 * request, so a session opened from it would lend nothing while looking like success), and a station
 * naming a device other than its own. Every leg sends a VALID manager username + PIN, so a refusal
 * cannot be the credential check. The till cashier leg is the control.
 */
@DisplayName("Manager sign-in on a station: who may open one")
class StationStepUpRefusedTest {
    private val stationDevice = "station-stepup-refused"
    private val tillDevice = "till-stepup-control"

    @BeforeEach
    fun resetState() {
        PairAttemptLimiter.resetAllForTests()
        TseAdminSession.clear()
        clearSessions()
    }

    @AfterEach
    fun clearSessions() {
        listOf(stationDevice, tillDevice).forEach { device ->
            TemporarySessionStore.getSessionByDevice(device)?.let { TemporarySessionStore.removeSession(it.sessionId) }
        }
    }

    /** The fixtures create no PIN, and a step-up is authorised by PIN. */
    private fun setPin(
        username: String,
        pin: String,
    ) = transaction {
        Employee.find { EmployeeTable.username eq username }.first().pin = pin
    }

    /** Pairs an ORDER_STATION to [stationDevice]; returns its device token. */
    private suspend fun pairStation(
        client: HttpClient,
        adminToken: String,
    ): String {
        val created =
            client
                .post("/api/v1/terminals") {
                    bearerAuth(adminToken)
                    setBody(TextContent("""{"name":"Station","role":"ORDER_STATION"}""", ContentType.Application.Json))
                }.let { testJson.decodeFromString<TerminalDto.TerminalResponseDto>(it.bodyAsText()) }
        val code =
            client
                .post("/api/v1/terminals/${created.id}/pairing-code") { bearerAuth(adminToken) }
                .let { testJson.decodeFromString<TerminalDto.PairingCodeResponseDto>(it.bodyAsText()) }
                .code
        return client
            .post("/api/v1/terminals/pair") {
                setBody(TextContent("""{"code":"$code","deviceId":"$stationDevice"}""", ContentType.Application.Json))
            }.let { testJson.decodeFromString<TerminalDto.PairDeviceResponseDto>(it.bodyAsText()) }
            .deviceToken
    }

    /** Exchanges [deviceToken] for the station JWT, as the station shell does at launch. */
    private suspend fun stationJwt(
        client: HttpClient,
        deviceToken: String,
    ): String {
        val session = client.post("/api/v1/terminals/session") { header("X-Device-Token", deviceToken) }
        assertEquals(HttpStatusCode.OK, session.status, session.bodyAsText())
        return testJson.decodeFromString<TerminalDto.StationSessionResponseDto>(session.bodyAsText()).accessToken
    }

    private suspend fun requestStepUp(
        client: HttpClient,
        device: String,
        authenticate: HttpRequestBuilder.() -> Unit,
    ): HttpResponse =
        client.post("/api/auth/temporary-session") {
            authenticate()
            header("X-Device-ID", device)
            setBody(
                TextContent(
                    """{"managerUsername":"manager","managerPin":"4321","deviceId":"$device"}""",
                    ContentType.Application.Json,
                ),
            )
        }

    private suspend fun requestStepUpFor(
        client: HttpClient,
        device: String,
        jwt: String,
    ): HttpResponse =
        client.post("/api/auth/temporary-session") {
            bearerAuth(jwt)
            setBody(
                TextContent(
                    """{"managerUsername":"manager","managerPin":"4321","deviceId":"$device"}""",
                    ContentType.Application.Json,
                ),
            )
        }

    private fun seed() {
        TestData.createAdmin()
        TestData.createManager()
        TestData.createCashier()
        TestData.createTerminalDeviceEmployee()
    }

    @Test
    fun `the station JWT gets a manager sign-in, scoped to Storno and transfer`() =
        testApp(setupData = { seed() }) { client ->
            setPin("manager", "4321")
            val exchange = client.post("/api/v1/terminals/session") { header("X-Device-Token", pairStation(client, TestAuthHelper.loginAsAdmin(client))) }
            val station = testJson.decodeFromString<TerminalDto.StationSessionResponseDto>(exchange.bodyAsText())
            assertFalse(Capability.SELF_WRITE.name in station.capabilities, "the station still holds no SELF_WRITE of its own")

            val response = requestStepUp(client, stationDevice) { bearerAuth(station.accessToken) }

            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            val stored = assertNotNull(TemporarySessionStore.getSessionByDevice(stationDevice))
            assertEquals(ElevationScope.STATION_OVERRIDES, stored.grantedCapabilities)
            assertTrue(stored.onStation, "a station sign-in is marked so its actions are attributed to the manager")
        }

    @Test
    fun `a station cannot open a sign-in for another device`() =
        testApp(setupData = { seed() }) { client ->
            setPin("manager", "4321")
            val jwt = stationJwt(client, pairStation(client, TestAuthHelper.loginAsAdmin(client)))

            val response = requestStepUpFor(client, tillDevice, jwt)

            assertEquals(HttpStatusCode.Forbidden, response.status, response.bodyAsText())
            assertNull(TemporarySessionStore.getSessionByDevice(tillDevice), "nothing is planted on the other device")
            assertNull(TemporarySessionStore.getSessionByDevice(stationDevice))
        }

    @Test
    fun `the bare station device token is refused a step-up and nothing is created`() =
        testApp(setupData = { seed() }) { client ->
            setPin("manager", "4321")
            val deviceToken = pairStation(client, TestAuthHelper.loginAsAdmin(client))

            val response = requestStepUp(client, stationDevice) { header("X-Device-Token", deviceToken) }

            assertTrue(
                response.status == HttpStatusCode.Forbidden || response.status == HttpStatusCode.Unauthorized,
                "the bare device token must be refused: ${response.status} ${response.bodyAsText()}",
            )
            assertNull(
                TemporarySessionStore.getSessionByDevice(stationDevice),
                "a refused step-up must not leave a session keyed on the station's device",
            )
        }

    @Test
    fun `a till cashier sending the same request gets the step-up`() =
        testApp(setupData = { seed() }) { client ->
            setPin("manager", "4321")
            val cashier = TestAuthHelper.loginAs(client, "cashier", deviceId = tillDevice)

            val response = requestStepUp(client, tillDevice) { bearerAuth(cashier) }

            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            assertNotNull(TemporarySessionStore.getSessionByDevice(tillDevice), "the control: a person's step-up is stored")
        }
}
