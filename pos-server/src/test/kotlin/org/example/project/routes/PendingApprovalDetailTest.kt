package org.example.project.routes

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
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.features.auth.data.TemporarySessionStore
import org.example.project.features.order.database.tables.OrderItemTable
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A manager who opens the approvals list AFTER a request was raised must see the same request the
 * live broadcast showed a manager who was already online (E2E-2026-09-15 N5).
 *
 * Both views are `AuthDto.ApprovalRequestDto`, but they used to be built at two different places:
 * `createApprovalRequest` resolved the employee number, the terminal name and the raw ids in
 * `details`, while `getPendingRequests` mapped the stored `PendingRequest` straight through and so
 * rendered a bare UUID, a raw device id and unresolved `itemIds`/`targetTableId` keys. The store
 * holds neither the employee number nor the terminal name, which is why the pending leg degraded.
 *
 * The request is raised through the real route rather than seeded into the store: a cashier holds
 * `ORDER_WRITE` (so the route gate admits the transfer) but not `TABLE_SESSION_OVERRIDE` (so
 * `requirePermissionOrDefer` parks it for a manager) — the exact shape the finding came from.
 * Transfer rather than cancel because only its `details` carries a target table AND an item list.
 */
@DisplayName("Pending approvals carry the same resolved detail as the live broadcast")
class PendingApprovalDetailTest {
    private val deviceId = "pending-detail-device"
    private val terminalClientId = "TER-PND"
    private val cashierNumber = "CSH-PND"

    /**
     * `TemporarySessionStore` is a process-wide object that outlives this test's database, so a
     * request parked here would otherwise still be pending for whichever class JUnit runs next.
     */
    private val raisedRequestIds = mutableListOf<String>()

    @AfterEach
    fun removeParkedRequests() {
        raisedRequestIds.forEach { TemporarySessionStore.removeRequest(it) }
        raisedRequestIds.clear()
    }

    @Test
    fun `the pending list names the employee, the terminal and the products, not their ids`() =
        testApp(
            setupData = {
                TestData.createAdmin()
                TestData.createCashier(employeeNumber = cashierNumber, username = "cashier")
                TestData.createDefaultVatRates()
                TestData.setTseMode(TseMode.SIMULATION)
                TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = terminalClientId)
            },
        ) { client ->
            // The device id is stamped into the JWT at login; that is the trusted identity the
            // approval path resolves the terminal from.
            val cashier = TestAuthHelper.loginAs(client, "cashier", deviceId = deviceId)

            val category = TestData.createCategory(name = "PND_Cat")
            val product =
                TestData.createProduct(
                    categoryId = category.id.value,
                    productName = "PND_Prod",
                    productNumber = "PND_P",
                )
            val source = TestData.createTableWithSession(tableNumber = "PND_T1")
            val target = TestData.createTable(tableNumber = "PND_T2")

            val created =
                client.post("/api/orders") {
                    bearerAuth(cashier)
                    setBody(
                        TextContent(
                            """{"tableInfoId": ${source.session.id.value}, "orderItems": [""" +
                                """{"productId": ${product.id.value}, "unitPrice": "9.99", "quantity": 1}]}""",
                            ContentType.Application.Json,
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())

            // `POST /api/orders` answers 201 with an empty body, so the item ids are read back from
            // this session rather than from the create response.
            val itemIds =
                transaction {
                    OrderItem.find { OrderItemTable.sessionId eq source.session.id }.map { it.id.value }
                }
            assertTrue(itemIds.isNotEmpty(), "the order must have produced at least one item")

            val deferred =
                client.post("/api/items/transfer/${source.table.id.value}") {
                    bearerAuth(cashier)
                    setBody(
                        TextContent(
                            """{"itemIds":[${itemIds.joinToString(",")}],"targetTableId":${target.id.value},"reason":"Test"}""",
                            ContentType.Application.Json,
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Accepted, deferred.status, deferred.bodyAsText())
            val requestId =
                testJson
                    .decodeFromString<ApiResponseDto.SuccessResponse<AuthDto.ApprovalRequestDto>>(deferred.bodyAsText())
                    .data!!
                    .requestId
            raisedRequestIds += requestId

            // The manager arrives afterwards and reads the queue — the leg that used to degrade.
            val admin = TestAuthHelper.loginAsAdmin(client)
            val pending = client.get("/api/auth/approvals/pending") { bearerAuth(admin) }
            assertEquals(HttpStatusCode.OK, pending.status, pending.bodyAsText())

            val listed =
                testJson
                    .decodeFromString<List<AuthDto.ApprovalRequestDto>>(pending.bodyAsText())
                    .single { it.requestId == requestId }

            assertEquals(
                cashierNumber,
                listed.requestingEmployeeNumber,
                "the queue must name the employee, not leave the manager with a bare UUID",
            )
            assertEquals(
                terminalClientId,
                listed.terminalName,
                "the queue must name the terminal, not the raw device id",
            )
            assertEquals(
                "PND_P-PND_Prod",
                listed.details["items"],
                "itemIds must be resolved to product names: ${listed.details}",
            )
            assertEquals(
                "PND_T2",
                listed.details["targetTable"],
                "targetTableId must be resolved to the table number: ${listed.details}",
            )
            assertFalse(
                "itemIds" in listed.details,
                "a raw itemIds key must not reach the manager: ${listed.details}",
            )
            assertFalse(
                "targetTableId" in listed.details,
                "a raw targetTableId key must not reach the manager: ${listed.details}",
            )
        }
}
