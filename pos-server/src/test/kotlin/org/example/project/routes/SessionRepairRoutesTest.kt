package org.example.project.routes

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.order.OrderStatus
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.table.domain.models.TableSession
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for the one-off admin repair endpoint that heals tables referencing a
 * completed table session (Policy A — re-open onto a fresh session when OPEN
 * items exist, otherwise clear the dangling pointer).
 */
@DisplayName("POST /api/tables/repair-completed-sessions")
class SessionRepairRoutesTest {
    @Nested
    @DisplayName("Policy A — re-open onto a fresh session")
    inner class ReopenWithOpenItems {
        @Test
        fun `moves OPEN items to a new active session and preserves the completed session`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    val vats = TestData.createDefaultVatRates()
                    val categoryId = TestData.createCategory().id.value
                    val product = TestData.createProduct(categoryId = categoryId)
                    val tws = TestData.createTableWithSession()
                    transaction {
                        val session = TableSession.findById(tws.session.id.value)!!
                        val table = session.table
                        val vat = vats.first()

                        // Seed one OPEN order + item on the (soon to be completed) session.
                        val order =
                            Orders.new {
                                this.orderNumber = "ORD-REPAIR-1"
                                this.employee = session.employeeId
                                this.tableSession = session
                                this.orderStatus = OrderStatus.CONFIRMED
                                this.orderTime = currentTimeStamp()
                                this.orderTotal = BigDecimal("9.9900")
                            }
                        OrderItem.new {
                            this.order = order
                            this.session = session
                            this.product = product
                            this.unitPrice = BigDecimal("9.9900")
                            this.finalPrice = BigDecimal("9.9900")
                            this.itemStatus = ItemStatus.OPEN
                            this.vat = vat
                            this.takeAway = false
                        }

                        // Corrupt state: session completed but table still OCCUPIED + pointing at it.
                        session.completed = true
                        session.endTime = currentTimeStamp()
                        table.tableStatus = TableStatus.OCCUPIED
                        table.currentSession = session
                    }
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)

                val response = client.post("/api/tables/repair-completed-sessions") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText().filterNot { it.isWhitespace() }
                assertTrue(body.contains("\"reopenedSessions\":1"), "expected one reopened session, got: $body")
                assertTrue(body.contains("\"movedOpenItems\":1"), "expected one moved item, got: $body")

                // Verify structural state after repair.
                transaction {
                    val oldSession = TableSession.findById(1L)!!
                    assertEquals(true, oldSession.completed, "old session must stay completed")

                    val table = oldSession.table
                    val newSession = table.currentSession
                    assertNotEquals(null, newSession, "table must have a fresh currentSession")
                    assertNotEquals(1L, newSession!!.id.value, "currentSession must be a NEW session")
                    assertEquals(false, newSession.completed, "new session must be active")
                    assertEquals(TableStatus.OCCUPIED, table.tableStatus)

                    // The OPEN item and its parent order must now belong to the new session.
                    val item = OrderItem.findById(1L)!!
                    assertEquals(newSession.id.value, item.session.id.value, "item moved to new session")
                    assertEquals(
                        newSession.id.value,
                        item.order!!
                            .tableSession.id.value,
                        "order moved to new session",
                    )
                    assertEquals(ItemStatus.OPEN, item.itemStatus)
                }
            }
    }

    @Nested
    @DisplayName("Policy A — clear dangling pointer (no OPEN items)")
    inner class ClearDanglingPointer {
        @Test
        fun `clears the pointer and frees the table when the completed session has no OPEN items`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    val tws = TestData.createTableWithSession()
                    transaction {
                        val session = TableSession.findById(tws.session.id.value)!!
                        val table = session.table
                        // Corrupt state (FREE-but-dangling variant left by the old ZBon heal):
                        // session completed, no OPEN items, table still points at it.
                        session.completed = true
                        session.endTime = currentTimeStamp()
                        table.tableStatus = TableStatus.FREE
                        table.currentSession = session
                    }
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)

                val response = client.post("/api/tables/repair-completed-sessions") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText().filterNot { it.isWhitespace() }
                assertTrue(body.contains("\"clearedDanglingPointers\":1"), "expected one cleared pointer, got: $body")

                transaction {
                    val table = TableSession.findById(1L)!!.table
                    assertEquals(null, table.currentSession, "dangling pointer must be cleared")
                    assertEquals(TableStatus.FREE, table.tableStatus)
                }
            }
    }

    @Nested
    @DisplayName("Idempotency")
    inner class Idempotency {
        @Test
        fun `second run against clean data is a no-op`() =
            testApp(
                setupData = {
                    TestData.createAdmin()
                    val tws = TestData.createTableWithSession()
                    transaction {
                        val session = TableSession.findById(tws.session.id.value)!!
                        val table = session.table
                        session.completed = true
                        session.endTime = currentTimeStamp()
                        table.tableStatus = TableStatus.FREE
                        table.currentSession = session
                    }
                },
            ) { client ->
                val token = TestAuthHelper.loginAsAdmin(client)

                val first = client.post("/api/tables/repair-completed-sessions") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, first.status)
                assertTrue(first.bodyAsText().filterNot { it.isWhitespace() }.contains("\"clearedDanglingPointers\":1"))

                val second = client.post("/api/tables/repair-completed-sessions") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, second.status)
                val body = second.bodyAsText().filterNot { it.isWhitespace() }
                assertTrue(body.contains("\"clearedDanglingPointers\":0"), "second run must be a no-op, got: $body")
                assertTrue(body.contains("\"reopenedSessions\":0"), "second run must be a no-op, got: $body")
                assertTrue(body.contains("\"movedOpenItems\":0"), "second run must be a no-op, got: $body")
            }
    }
}
