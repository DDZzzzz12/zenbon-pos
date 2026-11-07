package org.example.project.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import org.example.project.core.domain.constants.WellKnownLocationTypes
import org.example.project.core.domain.dto.table.TableSessionDto
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.error.PosErrorCode
import org.example.project.core.error.ProblemDetail
import org.example.project.core.websocket.PendingBillingRequestStore
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.table.database.tables.TableSessionTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.data.service.PairAttemptLimiter
import org.example.project.features.terminal.domain.TerminalEmployee
import org.example.project.features.tse.domain.session.TseAdminSession
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An order station never owns a table session (spec 2026-09-14). Every leg runs under a real station
 * JWT — paired over HTTP and exchanged at `POST /api/v1/terminals/session`. The guard keys on the acting
 * employee's role, and on the station path that employee is `terminal-device`.
 *
 * Every leg that changes the round-robin flag runs inside [TestData.withRoundRobinAssignment], which
 * resets it in a `finally`: the flag lives in `ShopConfigurationManager`'s process-wide cache and would
 * otherwise leak into whichever test class runs next.
 */
@DisplayName("Order station table-session ownership")
class StationTableOwnershipRouteTest {
    @BeforeEach
    fun resetState() {
        PairAttemptLimiter.resetAllForTests()
        TseAdminSession.clear()
        PendingBillingRequestStore.reset()
    }

    /** Pairs an ORDER_STATION to [deviceId] and exchanges its device token for a station JWT. */
    private suspend fun stationToken(
        client: HttpClient,
        deviceId: String,
    ): String {
        val admin = TestAuthHelper.loginAsAdmin(client)
        val created =
            client
                .post("/api/v1/terminals") {
                    bearerAuth(admin)
                    setBody(TextContent("""{"name":"Station $deviceId","role":"ORDER_STATION"}""", ContentType.Application.Json))
                }.let { testJson.decodeFromString<TerminalDto.TerminalResponseDto>(it.bodyAsText()) }
        val code =
            client
                .post("/api/v1/terminals/${created.id}/pairing-code") { bearerAuth(admin) }
                .let { testJson.decodeFromString<TerminalDto.PairingCodeResponseDto>(it.bodyAsText()) }
                .code
        val deviceToken =
            client
                .post("/api/v1/terminals/pair") {
                    setBody(TextContent("""{"code":"$code","deviceId":"$deviceId"}""", ContentType.Application.Json))
                }.let { testJson.decodeFromString<TerminalDto.PairDeviceResponseDto>(it.bodyAsText()) }
                .deviceToken
        val session = client.post("/api/v1/terminals/session") { header("X-Device-Token", deviceToken) }
        assertEquals(HttpStatusCode.OK, session.status, session.bodyAsText())
        return testJson.decodeFromString<TerminalDto.StationSessionResponseDto>(session.bodyAsText()).accessToken
    }

    private fun poolCashier(number: String): Employee {
        val emp = TestData.createCashier(employeeNumber = number, username = "u_$number")
        transaction { Employee.findById(emp.id)!!.inRotationPool = true }
        return emp
    }

    private fun productId(): Long {
        TestData.createDefaultVatRates()
        val category = TestData.createCategory(name = "STO_Cat")
        return TestData.createProduct(categoryId = category.id.value, productName = "STO_Prod", productNumber = "STO_P").id.value
    }

    private suspend fun order(
        client: HttpClient,
        token: String,
        tableId: Long,
        productId: Long,
    ): HttpResponse =
        client.post("/api/orders") {
            bearerAuth(token)
            setBody(
                TextContent(
                    """{"tableInfoId": 0, "tableId": $tableId, "orderItems": [""" +
                        """{"productId": $productId, "unitPrice": "9.99", "quantity": 1}]}""",
                    ContentType.Application.Json,
                ),
            )
        }

    private suspend fun openTable(
        client: HttpClient,
        token: String,
        tableId: Long,
        forEmployeeNumber: String? = null,
    ): HttpResponse {
        val query = forEmployeeNumber?.let { "?forEmployeeNumber=$it" } ?: ""
        return client.get("/api/tables/$tableId/currentSession$query") { bearerAuth(token) }
    }

    private suspend fun problemCode(response: HttpResponse): String? = testJson.decodeFromString<ProblemDetail>(response.bodyAsText()).code

    /** Employee number owning the table's ACTIVE session, or null while it has none. */
    private fun activeOwnerNumber(tableId: Long): String? =
        transaction {
            TableSession
                .find { (TableSessionTable.table eq tableId) and (TableSessionTable.completed eq false) }
                .firstOrNull()
                ?.employeeId
                ?.employeeNumber
        }

    @Test
    fun `an order on a free table with round-robin on opens a session owned by the rotated employee`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createTerminalDeviceEmployee()
                TestData.setTseMode(TseMode.SIMULATION)
            },
        ) { client ->
            val station = stationToken(client, "sto-order-rotation")
            poolCashier("RR001")
            val product = productId()
            val table = TestData.createTable(tableNumber = "STO_T1")

            TestData.withRoundRobinAssignment(true) {
                val response = order(client, station, table.id.value, product)

                assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
                assertEquals("RR001", activeOwnerNumber(table.id.value))
                val orderedBy =
                    transaction {
                        Orders
                            .all()
                            .maxByOrNull { it.id.value }!!
                            .employee.username
                    }
                assertEquals(TerminalEmployee.USERNAME, orderedBy, "orders stay attributed to the station (spec decision 7)")
            }
        }

    @Test
    fun `an order on a free table is refused without round-robin and on an empty pool`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createTerminalDeviceEmployee()
                TestData.setTseMode(TseMode.SIMULATION)
            },
        ) { client ->
            val station = stationToken(client, "sto-order-no-rotation")
            val product = productId()
            val table = TestData.createTable(tableNumber = "STO_T2")

            TestData.withRoundRobinAssignment(false) {
                val off = order(client, station, table.id.value, product)
                assertEquals(HttpStatusCode.Conflict, off.status, off.bodyAsText())
                assertEquals(PosErrorCode.STATION_TABLE_REQUIRES_ROTATION.name, problemCode(off))
                assertNull(activeOwnerNumber(table.id.value), "a refused open must leave the table free")

                TestData.setRoundRobinAssignment(true)
                val emptyPool = order(client, station, table.id.value, product)
                assertEquals(HttpStatusCode.Conflict, emptyPool.status, emptyPool.bodyAsText())
                assertEquals(PosErrorCode.ROTATION_POOL_EMPTY.name, problemCode(emptyPool))
                assertNull(activeOwnerNumber(table.id.value))
            }
        }

    @Test
    fun `an order never opens a free takeaway or virtual table`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createTerminalDeviceEmployee()
                TestData.setTseMode(TseMode.SIMULATION)
            },
        ) { client ->
            val station = stationToken(client, "sto-order-virtual")
            poolCashier("RR001")
            val product = productId()
            val takeaway = TestData.createTable(tableNumber = "STO_TAKE", locationTypeId = WellKnownLocationTypes.TAKEAWAY)
            val voucher = TestData.createTable(tableNumber = "STO_VOUCH", seats = 0, locationTypeId = WellKnownLocationTypes.VOUCHER)

            TestData.withRoundRobinAssignment(true) {
                for (tableId in listOf(takeaway.id.value, voucher.id.value)) {
                    val response = order(client, station, tableId, product)
                    assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
                    assertEquals(PosErrorCode.STATION_TABLE_VIRTUAL.name, problemCode(response))
                    assertNull(activeOwnerNumber(tableId))
                }
            }
        }

    @Test
    fun `an order books onto open takeaway and normal sessions without changing their owner`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createTerminalDeviceEmployee()
                TestData.setTseMode(TseMode.SIMULATION)
            },
        ) { client ->
            val station = stationToken(client, "sto-order-open")
            val product = productId()
            val openTakeaway =
                TestData.createTableWithSession(tableNumber = "STO_OTAKE", locationTypeId = WellKnownLocationTypes.TAKEAWAY)
            val openNormal = TestData.createTableWithSession(tableNumber = "STO_OT")

            // Off on purpose: reusing an open session must not depend on rotation.
            TestData.withRoundRobinAssignment(false) {
                for ((tableId, owner) in listOf(
                    openTakeaway.table.id.value to "TBL_STO_OTAKE",
                    openNormal.table.id.value to "TBL_STO_OT",
                )) {
                    val response = order(client, station, tableId, product)
                    assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
                    assertEquals(owner, activeOwnerNumber(tableId))
                }
            }
        }

    @Test
    fun `opening a table through currentSession follows the same rule`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createTerminalDeviceEmployee()
                TestData.setTseMode(TseMode.SIMULATION)
            },
        ) { client ->
            val station = stationToken(client, "sto-current-session")
            val rotatedOwner = poolCashier("RR001")
            val normal = TestData.createTable(tableNumber = "STO_CS_T")
            val takeaway = TestData.createTable(tableNumber = "STO_CS_TA", locationTypeId = WellKnownLocationTypes.TAKEAWAY)

            TestData.withRoundRobinAssignment(true) {
                val named = openTable(client, station, normal.id.value, forEmployeeNumber = "RR001")
                assertEquals(HttpStatusCode.Conflict, named.status, named.bodyAsText())
                assertEquals(PosErrorCode.STATION_TABLE_REQUIRES_ROTATION.name, problemCode(named))

                val virtual = openTable(client, station, takeaway.id.value)
                assertEquals(HttpStatusCode.Conflict, virtual.status, virtual.bodyAsText())
                assertEquals(PosErrorCode.STATION_TABLE_VIRTUAL.name, problemCode(virtual))

                TestData.setRoundRobinAssignment(false)
                val off = openTable(client, station, normal.id.value)
                assertEquals(HttpStatusCode.Conflict, off.status, off.bodyAsText())
                assertEquals(PosErrorCode.STATION_TABLE_REQUIRES_ROTATION.name, problemCode(off))

                TestData.setRoundRobinAssignment(true)
                val rotated = openTable(client, station, normal.id.value)
                assertEquals(HttpStatusCode.OK, rotated.status, rotated.bodyAsText())
                assertEquals("RR001", activeOwnerNumber(normal.id.value))
                // The response names the owner by employee number, never by username
                // (`u_RR001` here), which is what the fixture's distinct username catches.
                assertEquals(
                    rotatedOwner.employeeNumber,
                    testJson.decodeFromString<TableSessionDto.TableSessionResponseDto>(rotated.bodyAsText()).employeeNumber,
                )
            }
        }

    @Test
    fun `an order on a free table is refused when the rotated owner is in training mode`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createTerminalDeviceEmployee()
                TestData.setTseMode(TseMode.SIMULATION)
            },
        ) { client ->
            val station = stationToken(client, "sto-order-trainee")
            val trainee = poolCashier("RR_TRN")
            transaction { Employee.findById(trainee.id)!!.trainingMode = true }
            val product = productId()
            val table = TestData.createTable(tableNumber = "STO_TRN_T")

            TestData.withRoundRobinAssignment(true) {
                val response = order(client, station, table.id.value, product)

                assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
                val problem = testJson.decodeFromString<ProblemDetail>(response.bodyAsText())
                assertEquals(PosErrorCode.TRAINING_MODE_MISMATCH.name, problem.code)
                assertTrue(
                    problem.detail.orEmpty().contains("RR_TRN"),
                    "the refusal names the rotated owner, not a session the operator never saw: ${problem.detail}",
                )
                assertNull(activeOwnerNumber(table.id.value), "a station order must never open a training session")
            }
        }

    /**
     * The station account may not ask for the bill (OS-b — nothing is billed under it); a waiter
     * asks, and the request reaches the rotated owner, never the station.
     */
    @Test
    fun `a billing request on a station-opened table is raised at the till and reaches the rotated owner`() {
        lateinit var terminalId: String
        testApp(
            setupData = {
                TestData.createAdmin()
                terminalId =
                    TestData
                        .createTerminalDeviceEmployee()
                        .id.value
                        .toString()
                TestData.setTseMode(TseMode.SIMULATION)
            },
        ) { client ->
            val station = stationToken(client, "sto-billing-request")
            val owner = poolCashier("RR001")
            TestData.createCashier(employeeNumber = "W002", username = "waiter_w002")
            val product = productId()
            val table = TestData.createTable(tableNumber = "STO_BILL_T")

            TestData.withRoundRobinAssignment(true) {
                val opened = order(client, station, table.id.value, product)
                assertEquals(HttpStatusCode.Created, opened.status, opened.bodyAsText())

                val fromStation = client.post("/api/tables/${table.id.value}/request-billing") { bearerAuth(station) }
                assertEquals(HttpStatusCode.Forbidden, fromStation.status, fromStation.bodyAsText())
                assertTrue(
                    PendingBillingRequestStore.getForEmployee(owner.id.value.toString()).isEmpty(),
                    "a refused station request must reach nobody",
                )

                val waiter = TestAuthHelper.loginAs(client, "waiter_w002", deviceId = "sto-till-device")
                val fromTill = client.post("/api/tables/${table.id.value}/request-billing") { bearerAuth(waiter) }
                assertEquals(HttpStatusCode.OK, fromTill.status, fromTill.bodyAsText())
                assertTrue(
                    PendingBillingRequestStore.getForEmployee(owner.id.value.toString()).isNotEmpty(),
                    "the rotated owner bills the table, so the request is theirs",
                )
                assertTrue(
                    PendingBillingRequestStore.getForEmployee(terminalId).isEmpty(),
                    "a station cannot bill, so it must never be asked to",
                )
            }
        }
    }
}
