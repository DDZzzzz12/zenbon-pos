package org.example.project.features.order

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import org.example.project.core.domain.dto.ApiResponseDto
import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.auth.data.TemporarySessionStore
import org.example.project.features.order.database.tables.ItemTransferTable
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@DisplayName("Transfer items — a reason is required and persisted")
class TransferItemsReasonTest {
    private val deviceId = "transfer-reason-device"
    private val parked = mutableListOf<String>()

    @AfterEach
    fun removeParkedRequests() {
        parked.forEach { TemporarySessionStore.removeRequest(it) }
        parked.clear()
    }

    private fun body(
        itemId: Long,
        targetTableId: Long,
        reason: String?,
    ): TextContent {
        val reasonJson = if (reason == null) "" else ""","reason":"$reason""""
        return TextContent(
            """{"itemIds":[$itemId],"targetTableId":$targetTableId$reasonJson}""",
            ContentType.Application.Json,
        )
    }

    private fun transferRows() = transaction { ItemTransferTable.selectAll().map { it[ItemTransferTable.reason] } }

    @Test
    fun `a transfer without a reason is refused with 400 and moves nothing`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.setTseMode(TseMode.SIMULATION)
                TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TER-TR1")
            },
        ) { client ->
            val admin = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
            val source = TestData.createTableWithSession(tableNumber = "TR1_A")
            val target = TestData.createTable(tableNumber = "TR1_B")
            val item = TestData.createOrderItemInSession(source.session, ItemStatus.OPEN)

            val response =
                client.post("/api/items/transfer/${source.table.id.value}") {
                    bearerAuth(admin)
                    setBody(body(item.id.value, target.id.value, reason = null))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            assertEquals(emptyList(), transferRows(), "no transfer may be recorded")
            assertEquals(
                source.session.id.value,
                transaction {
                    OrderItem
                        .findById(item.id)!!
                        .session.id.value
                },
            )
        }

    @Test
    fun `a transfer with a reason stores it on the item_transfer row`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.setTseMode(TseMode.SIMULATION)
                TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TER-TR2")
            },
        ) { client ->
            val admin = TestAuthHelper.loginAs(client, "admin", deviceId = deviceId)
            val source = TestData.createTableWithSession(tableNumber = "TR2_A")
            val target = TestData.createTable(tableNumber = "TR2_B")
            val item = TestData.createOrderItemInSession(source.session, ItemStatus.OPEN)

            val response =
                client.post("/api/items/transfer/${source.table.id.value}") {
                    bearerAuth(admin)
                    setBody(body(item.id.value, target.id.value, reason = "Gast hat Platz gewechselt"))
                }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertEquals(listOf<String?>("Gast hat Platz gewechselt"), transferRows())
        }

    @Test
    fun `a transfer that needs approval is refused before parking when the reason is missing`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createCashier(employeeNumber = "CSH-TR3", username = "cashier")
                TestData.setTseMode(TseMode.SIMULATION)
                TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TER-TR3")
            },
        ) { client ->
            val cashier = TestAuthHelper.loginAs(client, "cashier", deviceId = deviceId)
            val source = TestData.createTableWithSession(tableNumber = "TR3_A")
            val target = TestData.createTable(tableNumber = "TR3_B")
            val item = TestData.createOrderItemInSession(source.session, ItemStatus.OPEN)

            val response =
                client.post("/api/items/transfer/${source.table.id.value}") {
                    bearerAuth(cashier)
                    setBody(body(item.id.value, target.id.value, reason = null))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        }

    @Test
    fun `the parked approval request shows the reason to the manager`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createCashier(employeeNumber = "CSH-TR4", username = "cashier")
                TestData.setTseMode(TseMode.SIMULATION)
                TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "TER-TR4")
            },
        ) { client ->
            val cashier = TestAuthHelper.loginAs(client, "cashier", deviceId = deviceId)
            val source = TestData.createTableWithSession(tableNumber = "TR4_A")
            val target = TestData.createTable(tableNumber = "TR4_B")
            val item = TestData.createOrderItemInSession(source.session, ItemStatus.OPEN)

            val deferred =
                client.post("/api/items/transfer/${source.table.id.value}") {
                    bearerAuth(cashier)
                    setBody(body(item.id.value, target.id.value, reason = "Kellnerwechsel"))
                }
            assertEquals(HttpStatusCode.Accepted, deferred.status, deferred.bodyAsText())
            val requestId =
                testJson
                    .decodeFromString<ApiResponseDto.SuccessResponse<AuthDto.ApprovalRequestDto>>(deferred.bodyAsText())
                    .data!!
                    .requestId
            parked += requestId

            val admin = TestAuthHelper.loginAsAdmin(client)
            val pending = client.get("/api/auth/approvals/pending") { bearerAuth(admin) }
            val listed =
                testJson
                    .decodeFromString<List<AuthDto.ApprovalRequestDto>>(pending.bodyAsText())
                    .single { it.requestId == requestId }
            assertEquals("Kellnerwechsel", listed.details["reason"], listed.details.toString())
        }
}
