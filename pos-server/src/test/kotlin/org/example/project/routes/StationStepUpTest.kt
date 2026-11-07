package org.example.project.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import org.example.project.core.auth.ElevationScope
import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.error.PosErrorCode
import org.example.project.features.audit.database.tables.TransactionAuditLogTable
import org.example.project.features.auth.data.TemporarySessionStore
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.database.tables.ItemTransferTable
import org.example.project.features.order.database.tables.OrderItemTable
import org.example.project.features.order.domain.models.ItemTransfer
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.terminal.data.service.PairAttemptLimiter
import org.example.project.features.terminal.domain.TerminalEmployee
import org.example.project.features.tse.domain.session.TseAdminSession
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentInstant
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * Corrections after send on an order station — owner decision 2026-10-03 (emu21 NOTE-v).
 *
 * „Station should not be able to request approval for Storno, but when a manager is temporary
 * logged in, the manager could approve a Storno or transfer."
 *
 * - **Server level 1 — no Storno request is ever queued from a station.** Removing a sent item needs
 *   ORDER_ITEM_CANCEL, which neither TERMINAL_DEVICE nor the station mask holds. The cancel used to
 *   DEFER (202, pending manager approval) and, with no manager online, expired after three minutes.
 *   It is now refused with 403 `STATION_STORNO_REQUIRES_MANAGER`, and nothing is queued.
 * - **Server level 2 — a manager's temporary sign-in on the station.** The manager enters number +
 *   PIN at `POST /api/auth/temporary-session` under the station's own JWT. The lend is scoped to
 *   [ElevationScope.STATION_OVERRIDES], and the Storno / item transfer it unlocks are attributed to
 *   the MANAGER — the cancellation row, the transfer row and the audit row — not the station account.
 * - When the session ends (ended by the station, or expired) the station is back to station rights.
 *
 * The station JWT is obtained the shipped way: pair over real HTTP, then exchange the device token
 * at `POST /api/v1/terminals/session`. Minting one directly would prove the mask, not the path.
 */
@OptIn(ExperimentalTime::class)
@DisplayName("Manager sign-in on a station")
class StationStepUpTest {
    private val deviceId = "station-stepup-device"

    /** A step-up granted on the same device but to somebody else. Must not elevate the station. */
    private val otherEmployeeSessionId = "SESSION-station-stepup-other-employee"

    @BeforeEach
    fun resetState() {
        PairAttemptLimiter.resetAllForTests()
        TseAdminSession.clear()
        clearSessions()
    }

    @AfterEach
    fun clearSessions() {
        TemporarySessionStore.removeSession(otherEmployeeSessionId)
        TemporarySessionStore.getSessionByDevice(deviceId)?.let { TemporarySessionStore.removeSession(it.sessionId) }
    }

    private fun seed() {
        TestData.createAdmin()
        TestData.createManager()
        TestData.createTerminalDeviceEmployee()
        TestData.createDefaultVatRates()
        TestData.setTseMode(TseMode.SIMULATION)
    }

    /** Pairs an ORDER_STATION to [deviceId] and exchanges its device token for a station JWT. */
    private suspend fun stationSession(
        client: HttpClient,
        adminToken: String,
    ): TerminalDto.StationSessionResponseDto {
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
        val deviceToken =
            client
                .post("/api/v1/terminals/pair") {
                    setBody(TextContent("""{"code":"$code","deviceId":"$deviceId"}""", ContentType.Application.Json))
                }.let { testJson.decodeFromString<TerminalDto.PairDeviceResponseDto>(it.bodyAsText()) }
                .deviceToken
        val session = client.post("/api/v1/terminals/session") { header("X-Device-Token", deviceToken) }
        assertEquals(HttpStatusCode.OK, session.status, session.bodyAsText())
        return testJson.decodeFromString<TerminalDto.StationSessionResponseDto>(session.bodyAsText())
    }

    private fun idOf(username: String): String =
        transaction {
            Employee
                .find { EmployeeTable.username eq username }
                .first()
                .id.value
                .toString()
        }

    private fun terminalEmployeeId(): String =
        transaction {
            TerminalEmployee
                .requireRow()
                .id.value
                .toString()
        }

    /** The fixtures create no PIN, and a manager signs in on the station by number + PIN. */
    private fun setManagerPin() =
        transaction {
            Employee.find { EmployeeTable.username eq "manager" }.first().pin = "4321"
        }

    /** The manager's temporary sign-in, sent the shipped way: under the station's own JWT. */
    private suspend fun managerSignsIn(
        client: HttpClient,
        station: String,
        requestedCapability: String? = null,
    ): HttpResponse {
        val capability = requestedCapability?.let { ""","requestedCapability":"$it"""" } ?: ""
        return client.post("/api/auth/temporary-session") {
            bearerAuth(station)
            setBody(
                TextContent(
                    """{"managerUsername":"manager","managerPin":"4321","deviceId":"$deviceId"$capability}""",
                    ContentType.Application.Json,
                ),
            )
        }
    }

    /** Books one item on a fresh table under the station JWT; returns (tableId, itemIds). */
    private suspend fun stationBooks(
        client: HttpClient,
        station: String,
        tableNumber: String,
    ): Pair<Long, List<Long>> {
        val category = TestData.createCategory(name = "${tableNumber}_Cat")
        val product =
            TestData.createProduct(
                categoryId = category.id.value,
                productName = "${tableNumber}_Prod",
                productNumber = "${tableNumber}_P",
            )
        val tws = TestData.createTableWithSession(tableNumber = tableNumber)
        val created =
            client.post("/api/orders") {
                bearerAuth(station)
                setBody(
                    TextContent(
                        """{"tableInfoId": ${tws.session.id.value}, "orderItems": [""" +
                            """{"productId": ${product.id.value}, "unitPrice": "9.99", "quantity": 1}, """ +
                            """{"productId": ${product.id.value}, "unitPrice": "4.50", "quantity": 1}]}""",
                        ContentType.Application.Json,
                    ),
                )
            }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        // Scoped to THIS session's items: `POST /api/orders` answers 201 with an empty body.
        val itemIds = transaction { OrderItem.find { OrderItemTable.sessionId eq tws.session.id }.map { it.id.value } }
        assertTrue(itemIds.size >= 2, "the order must have produced two items, got $itemIds")
        return tws.table.id.value to itemIds.sorted()
    }

    private suspend fun cancel(
        client: HttpClient,
        station: String,
        tableId: Long,
        itemId: Long,
    ): HttpResponse =
        client.post("/api/items/cancel/$tableId") {
            bearerAuth(station)
            setBody(TextContent("""{"itemIds":[$itemId],"reason":"wrong table"}""", ContentType.Application.Json))
        }

    private suspend fun assertStornoRefused(response: HttpResponse) {
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.Forbidden, response.status, body)
        assertTrue(PosErrorCode.STATION_STORNO_REQUIRES_MANAGER.name in body, "the refusal names the station-Storno rule: $body")
    }

    private fun pendingRequestCount(): Int = TemporarySessionStore.getPendingRequests().size

    @Test
    fun `a Storno from the station is refused and nothing is queued`() =
        testApp(setupData = { seed() }) { client ->
            val session = stationSession(client, TestAuthHelper.loginAsAdmin(client))
            assertFalse(
                Capability.ORDER_ITEM_CANCEL.name in session.capabilities,
                "the premise: the station mask does not hold ORDER_ITEM_CANCEL",
            )
            val (tableId, itemIds) = stationBooks(client, session.accessToken, "SS_T1")
            val pendingBefore = pendingRequestCount()

            assertStornoRefused(cancel(client, session.accessToken, tableId, itemIds.first()))
            assertEquals(pendingBefore, pendingRequestCount(), "a refused station Storno must not queue an approval request")
            assertNull(transaction { OrderItem.findById(itemIds.first())!!.cancellation }, "the item is untouched")

            // A step-up on the SAME device granted to somebody else — the manager's own id — must not
            // elevate the station. A server matching on the device alone would; the cancel stays refused.
            val managerId = idOf("manager")
            TemporarySessionStore.addSession(
                TemporarySessionStore.SessionInfo(
                    sessionId = otherEmployeeSessionId,
                    managerId = managerId,
                    managerName = "Test Manager",
                    managerRole = EmployeeRole.MANAGER,
                    deviceId = deviceId,
                    requestingEmployeeId = managerId,
                    grantedCapabilities = setOf(Capability.ORDER_ITEM_CANCEL),
                    createdAt = currentInstant(),
                    expiresAt = currentInstant().plus(5.minutes),
                    onStation = true,
                ),
            )
            assertStornoRefused(cancel(client, session.accessToken, tableId, itemIds.first()))
            assertNull(transaction { OrderItem.findById(itemIds.first())!!.cancellation }, "still untouched")
            assertEquals(pendingBefore, pendingRequestCount())
        }

    @Test
    fun `a manager signed in on the station performs the Storno, attributed to the manager, until the sign-in ends`() =
        testApp(setupData = { seed() }) { client ->
            setManagerPin()
            val station = stationSession(client, TestAuthHelper.loginAsAdmin(client)).accessToken
            val (tableId, itemIds) = stationBooks(client, station, "SS_T2")
            val managerId = idOf("manager")

            val signIn = managerSignsIn(client, station)
            assertEquals(HttpStatusCode.Created, signIn.status, signIn.bodyAsText())
            val stepUp = testJson.decodeFromString<AuthDto.TemporarySessionResponseDto>(signIn.bodyAsText())
            assertEquals(terminalEmployeeId(), stepUp.requestingEmployeeId, "lent to the station account on this device")
            val stored = assertNotNull(TemporarySessionStore.getSessionByDevice(deviceId))
            assertEquals(ElevationScope.STATION_OVERRIDES, stored.grantedCapabilities, "a station sign-in lends Storno + transfer only")
            assertTrue(stored.onStation)

            val executed = cancel(client, station, tableId, itemIds[0])
            assertEquals(HttpStatusCode.OK, executed.status, executed.bodyAsText())
            val cancelledBy =
                transaction {
                    OrderItem
                        .findById(itemIds[0])!!
                        .cancellation
                        ?.employeeId
                        ?.id
                        ?.value
                        ?.toString()
                }
            assertEquals(managerId, cancelledBy, "the Storno is the manager's, not the station account's")
            val audit =
                transaction {
                    TransactionAuditLogTable
                        .selectAll()
                        .where { TransactionAuditLogTable.operation eq "CANCEL_ORDER_ITEMS" }
                        .orderBy(TransactionAuditLogTable.id to SortOrder.DESC)
                        .first()
                }
            assertEquals(managerId, audit[TransactionAuditLogTable.employeeId], "the audit actor is the manager")
            assertEquals("TEMPORARY_SESSION", audit[TransactionAuditLogTable.authorizationType])
            assertEquals(managerId, audit[TransactionAuditLogTable.authorizingManagerId])

            // The station ends the sign-in: back to station rights, and refused again — not queued.
            val ended = client.delete("/api/auth/temporary-session/${stepUp.sessionId}") { bearerAuth(station) }
            assertEquals(HttpStatusCode.NoContent, ended.status, ended.bodyAsText())
            val pendingBefore = pendingRequestCount()
            assertStornoRefused(cancel(client, station, tableId, itemIds[1]))
            assertNull(transaction { OrderItem.findById(itemIds[1])!!.cancellation })
            assertEquals(pendingBefore, pendingRequestCount())
        }

    @Test
    fun `after the sign-in expires the station is refused again`() =
        testApp(setupData = { seed() }) { client ->
            setManagerPin()
            val station = stationSession(client, TestAuthHelper.loginAsAdmin(client)).accessToken
            val (tableId, itemIds) = stationBooks(client, station, "SS_T3")

            assertEquals(HttpStatusCode.Created, managerSignsIn(client, station).status)
            val live = assertNotNull(TemporarySessionStore.getSessionByDevice(deviceId))
            // Expire it in place — the same session, its lifetime run out.
            TemporarySessionStore.addSession(live.copy(expiresAt = currentInstant().minus(1.seconds)))

            assertStornoRefused(cancel(client, station, tableId, itemIds.first()))
            assertNull(transaction { OrderItem.findById(itemIds.first())!!.cancellation })
        }

    @Test
    fun `a manager signed in on the station transfers items, attributed to the manager`() =
        testApp(setupData = { seed() }) { client ->
            setManagerPin()
            val station = stationSession(client, TestAuthHelper.loginAsAdmin(client)).accessToken
            val (sourceTableId, itemIds) = stationBooks(client, station, "SS_T4")
            val target = TestData.createTableWithSession(tableNumber = "SS_T5")
            val managerId = idOf("manager")

            assertEquals(HttpStatusCode.Created, managerSignsIn(client, station).status)
            val moved =
                client.post("/api/items/transfer/$sourceTableId") {
                    bearerAuth(station)
                    setBody(
                        TextContent(
                            """{"itemIds":[${itemIds.first()}],"targetTableId":${target.table.id.value},"reason":"Test"}""",
                            ContentType.Application.Json,
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, moved.status, moved.bodyAsText())
            val movedBy =
                transaction {
                    ItemTransfer
                        .all()
                        .orderBy(ItemTransferTable.id to SortOrder.DESC)
                        .first()
                        .employee.id.value
                        .toString()
                }
            assertEquals(managerId, movedBy, "the transfer is the manager's, not the station account's")
        }

    @Test
    fun `a manager signed in on the station moves a whole table and the audit row names the sign-in`() =
        testApp(setupData = { seed() }) { client ->
            setManagerPin()
            val station = stationSession(client, TestAuthHelper.loginAsAdmin(client)).accessToken
            val (sourceTableId, _) = stationBooks(client, station, "SS_T6")
            val target = TestData.createTable(tableNumber = "SS_T7")
            val owner = TestData.createCashier(employeeNumber = "SS_OWN", username = "ss_own")
            val managerId = idOf("manager")

            assertEquals(HttpStatusCode.Created, managerSignsIn(client, station).status)
            val moved =
                client.post("/api/tables/$sourceTableId/transfer") {
                    bearerAuth(station)
                    setBody(
                        TextContent(
                            """{"targetTableId":${target.id.value},"targetEmployeeNumber":"${owner.employeeNumber}","reason":"Test"}""",
                            ContentType.Application.Json,
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, moved.status, moved.bodyAsText())
            val audit =
                transaction {
                    TransactionAuditLogTable
                        .selectAll()
                        .where { TransactionAuditLogTable.operation eq "TRANSFER_TABLE" }
                        .orderBy(TransactionAuditLogTable.id to SortOrder.DESC)
                        .first()
                }
            assertEquals(managerId, audit[TransactionAuditLogTable.employeeId], "the move is the manager's")
            assertEquals("TEMPORARY_SESSION", audit[TransactionAuditLogTable.authorizationType])
            assertEquals(managerId, audit[TransactionAuditLogTable.authorizingManagerId])
        }

    @Test
    fun `a station sign-in cannot be asked for anything beyond Storno and transfer`() =
        testApp(setupData = { seed() }) { client ->
            setManagerPin()
            val station = stationSession(client, TestAuthHelper.loginAsAdmin(client)).accessToken

            val voidAsked = managerSignsIn(client, station, requestedCapability = Capability.BILL_VOID.name)
            assertEquals(HttpStatusCode.Forbidden, voidAsked.status, voidAsked.bodyAsText())
            assertNull(TemporarySessionStore.getSessionByDevice(deviceId), "nothing is lent")
        }
}
