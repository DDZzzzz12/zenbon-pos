package org.example.project.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.features.auth.data.TemporarySessionStore
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.terminal.database.tables.TerminalTable
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentInstant
import org.example.project.utils.formatter.getTimeZone
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

/**
 * A4 Tasks 5 + 6: a login-less order station authenticates POST /api/orders with its
 * pairing-issued device token (`X-Device-Token`) — no bearer JWT — and the created order is
 * attributed to the shared terminal-employee (username "terminal-device",
 * [EmployeeRole.TERMINAL_DEVICE]).
 */
@OptIn(ExperimentalTime::class)
@DisplayName("Device-Principal Order Creation (A4 Task 5+6)")
class DevicePrincipalOrderTest {
    private data class PairedStation(
        val clientId: String,
        val deviceToken: String,
    )

    /** Provisions a fiscal ORDER_STATION terminal via the real HTTP pairing flow. */
    private suspend fun pairOrderStation(
        client: HttpClient,
        adminToken: String,
        deviceId: String,
    ): PairedStation {
        val id =
            client
                .post("/api/v1/terminals") {
                    bearerAuth(adminToken)
                    setBody(TextContent("""{"name":"Station","role":"ORDER_STATION"}""", ContentType.Application.Json))
                }.bodyAsText()
                .let { Regex(""""id":\s*(\d+)""").find(it)!!.groupValues[1] }

        val code =
            client
                .post("/api/v1/terminals/$id/pairing-code") {
                    bearerAuth(adminToken)
                }.bodyAsText()
                .let { Regex(""""code":\s*"(\d{6})"""").find(it)!!.groupValues[1] }

        val pairBody =
            client
                .post("/api/v1/terminals/pair") {
                    setBody(TextContent("""{"code":"$code","deviceId":"$deviceId"}""", ContentType.Application.Json))
                }.bodyAsText()

        val clientId = Regex(""""terminalId":\s*"(TER-\d+)"""").find(pairBody)!!.groupValues[1]
        val deviceToken = Regex(""""deviceToken":\s*"([^"]+)"""").find(pairBody)!!.groupValues[1]
        return PairedStation(clientId, deviceToken)
    }

    private val deviceId = "device-principal-station"

    @Test
    fun `POST orders with a valid X-Device-Token and no JWT creates the order as the terminal-employee`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createTerminalDeviceEmployee()
            },
        ) { client ->
            val adminToken = TestAuthHelper.loginAsAdmin(client)
            val station = pairOrderStation(client, adminToken, deviceId)

            val category = TestData.createCategory(name = "DP_Cat")
            val product =
                TestData.createProduct(
                    categoryId = category.id.value,
                    productName = "DP_Prod",
                    productNumber = "DP_P",
                )
            TestData.createDefaultVatRates()
            val tws = TestData.createTableWithSession(tableNumber = "DP_T1")

            val orderJson =
                """
                {"tableInfoId": ${tws.session.id.value}, "orderItems": [{"productId": ${product.id.value}, "unitPrice": 9.99, "quantity": 1}]}
                """.trimIndent()

            val response =
                client.post("/api/orders") {
                    header("X-Device-Token", station.deviceToken)
                    setBody(TextContent(orderJson, ContentType.Application.Json))
                }

            assertEquals(HttpStatusCode.Created, response.status)

            // CRITICAL: catches the deviceId/clientId swap — the order's terminal must be the
            // PAIRED STATION (resolved via the physical deviceId), and the attributed employee
            // must be the shared terminal-employee, not some other principal.
            val (terminalClientId, employeeUsername, employeeRole) =
                transaction {
                    val order = Orders.all().maxByOrNull { it.id.value }!!
                    Triple(order.terminal?.clientId, order.employee.username, order.employee.role)
                }
            assertEquals(station.clientId, terminalClientId)
            assertEquals("terminal-device", employeeUsername)
            assertEquals(EmployeeRole.TERMINAL_DEVICE, employeeRole)
        }

    @Test
    fun `a live device-scoped manager step-up session does NOT elevate a device-principal order`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createTerminalDeviceEmployee()
            },
        ) { client ->
            val adminToken = TestAuthHelper.loginAsAdmin(client)
            val stationDeviceId = "device-principal-station-elevation"
            val station = pairOrderStation(client, adminToken, stationDeviceId)
            val manager = TestData.createManager()

            // Simulate a live cashier step-up session KEYED ON THE SAME PHYSICAL DEVICE ID as the
            // shared login-less station — this is exactly the MAJOR-bug scenario: any concurrent
            // device-principal order on this station must NOT inherit the manager's role/identity
            // just because some other logged-in session on this device stepped up.
            TemporarySessionStore.addSession(
                TemporarySessionStore.SessionInfo(
                    sessionId = "SESSION-device-elevation-test",
                    managerId = transaction { manager.id.value.toString() },
                    managerName = "Test Manager",
                    managerRole = EmployeeRole.MANAGER,
                    deviceId = stationDeviceId,
                    requestingEmployeeId = "REQUESTING-EMPLOYEE-TEST",
                    grantedCapabilities = setOf(Capability.BILL_VOID),
                    createdAt = currentInstant(),
                    expiresAt = currentInstant().plus(5.minutes),
                ),
            )

            val category = TestData.createCategory(name = "DP_Cat_Elev")
            val product =
                TestData.createProduct(
                    categoryId = category.id.value,
                    productName = "DP_Prod_Elev",
                    productNumber = "DP_P_ELEV",
                )
            TestData.createDefaultVatRates()
            val tws = TestData.createTableWithSession(tableNumber = "DP_T_ELEV")

            val orderJson =
                """
                {"tableInfoId": ${tws.session.id.value}, "orderItems": [{"productId": ${product.id.value}, "unitPrice": 9.99, "quantity": 1}]}
                """.trimIndent()

            val response =
                client.post("/api/orders") {
                    header("X-Device-Token", station.deviceToken)
                    setBody(TextContent(orderJson, ContentType.Application.Json))
                }

            assertEquals(HttpStatusCode.Created, response.status)

            // The order must STILL be attributed to the shared terminal-employee, NOT the manager
            // who stepped up elsewhere on the same physical device.
            val (employeeUsername, employeeRole) =
                transaction {
                    val order = Orders.all().maxByOrNull { it.id.value }!!
                    order.employee.username to order.employee.role
                }
            assertEquals("terminal-device", employeeUsername)
            assertEquals(EmployeeRole.TERMINAL_DEVICE, employeeRole)
        }

    @Test
    fun `POST orders with an unknown X-Device-Token is rejected`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createTerminalDeviceEmployee()
            },
        ) { client ->
            val response =
                client.post("/api/orders") {
                    header("X-Device-Token", "totally-bogus-token")
                    setBody(TextContent("{}", ContentType.Application.Json))
                }
            // The terminal-capability ceiling (A4 Task 3) runs in onCall BEFORE routing/authenticate
            // for this exact transactional route: an unresolvable device identity is refused as
            // "unpaired" (409) before the device-principal auth provider is ever consulted.
            assertEquals(HttpStatusCode.Conflict, response.status)
        }

    @Test
    fun `POST orders with no X-Device-Token and no JWT is rejected`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createTerminalDeviceEmployee()
            },
        ) { client ->
            val response =
                client.post("/api/orders") {
                    setBody(TextContent("{}", ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.Conflict, response.status)
        }

    @Test
    fun `a valid device token that is expired is rejected with 401`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createTerminalDeviceEmployee()
            },
        ) { client ->
            val adminToken = TestAuthHelper.loginAsAdmin(client)
            val station = pairOrderStation(client, adminToken, "device-principal-expired")

            // Force the just-issued token to be expired, bypassing the pairing TTL default.
            transaction {
                val terminal = Terminal.find { TerminalTable.clientId eq station.clientId }.first()
                terminal.deviceTokenExpiresAt = currentInstant().minus(1.days).toLocalDateTime(getTimeZone())
            }

            val response =
                client.post("/api/orders") {
                    header("X-Device-Token", station.deviceToken)
                    setBody(TextContent("{}", ContentType.Application.Json))
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
}
