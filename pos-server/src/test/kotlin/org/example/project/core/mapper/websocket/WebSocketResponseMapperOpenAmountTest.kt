package org.example.project.core.mapper.websocket

import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit test for [sessionOpenAmount] — the open-amount helper used by
 * [createTableStatusUpdateDto] and broadcast over SSE.
 *
 * Contract: SUM(finalPrice) of the session's order items whose itemStatus == OPEN,
 * rendered 2-dp via toDecimalString; null when nothing is open (no OPEN items, or the
 * OPEN total is <= 0).
 */
class WebSocketResponseMapperOpenAmountTest {
    @BeforeEach
    fun setup() = TestDatabase.setup()

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    /** Builds an OPEN/PAID/CANCELLED item mix on a fresh session and returns the session id. */
    private fun seedSession(items: List<Pair<String, ItemStatus>>): Long {
        val employee = TestData.createCashier(employeeNumber = "SOA_EMP", username = "soa_emp")
        val category = TestData.createCategory(name = "SOA_Cat")
        val product =
            TestData.createProduct(
                categoryId = category.id.value,
                productName = "SOA_Prod",
                productNumber = "SOA_P",
            )
        val vat = TestData.createDefaultVatRates().first()
        val table = TestData.createTable(tableNumber = "SOA_T")

        return transaction {
            val session = RTable.findById(table.id.value)!!.startSession(employee)
            items.forEach { (price, status) ->
                OrderItem.new {
                    this.session = session
                    this.product = product
                    this.unitPrice = BigDecimal(price)
                    this.finalPrice = BigDecimal(price)
                    this.itemStatus = status
                    this.vat = vat
                    this.discount = BigDecimal.ONE
                    this.customPrice = false
                    this.takeAway = false
                    this.transferred = false
                    this.sideNotes = null
                }
            }
            session.id.value
        }
    }

    @Test
    fun `sums only OPEN items and formats to two decimals`() {
        val sessionId =
            seedSession(
                listOf(
                    "10.00" to ItemStatus.OPEN,
                    "5.50" to ItemStatus.OPEN,
                    "3.00" to ItemStatus.PAID,
                    "2.00" to ItemStatus.CANCELLED,
                ),
            )
        transaction {
            val session = TableSession.findById(sessionId)!!
            // 10.00 + 5.50 = 15.50 (PAID and CANCELLED excluded), rendered 2-dp.
            assertEquals("15.50", sessionOpenAmount(session))
        }
    }

    @Test
    fun `null when no OPEN items remain`() {
        val sessionId =
            seedSession(
                listOf(
                    "7.00" to ItemStatus.PAID,
                    "4.00" to ItemStatus.CANCELLED,
                ),
            )
        transaction {
            val session = TableSession.findById(sessionId)!!
            assertNull(sessionOpenAmount(session))
        }
    }

    @Test
    fun `null when session has no items at all`() {
        val sessionId = seedSession(emptyList())
        transaction {
            val session = TableSession.findById(sessionId)!!
            assertNull(sessionOpenAmount(session))
        }
    }
}
