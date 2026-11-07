package org.example.project.service

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.domain.service.OrderService
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.product.domain.models.Product
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertTrue

/**
 * Concurrency regression for the project-wide TABLE-before-SESSION lock order.
 *
 * The residual race this pins: `OrderServiceImpl.createOrder` (tableId path) and
 * `BillServiceImpl.createBill`/`completeSession` used to share no common lock — the
 * order path locked only the table row, the bill path only the session row — so a
 * bill could complete a session on its snapshot while an order attached fresh OPEN
 * items to that same session, leaving the corrupt "completed session with OPEN
 * items" state.
 *
 * Both paths now acquire the TABLE row lock before the SESSION row lock, so they
 * serialize on the table row. This test fires `createBill` (which completes the
 * session) and `createOrder` (which attaches a new OPEN item via the tableId path)
 * concurrently over many independent tables and asserts the invariant that no
 * completed session is ever left holding an OPEN item — whichever way each pair
 * interleaves. Reaching the assertions also demonstrates the consistent lock order
 * does not deadlock (a genuine AB/BA inversion would hang / time out).
 *
 * Runs entirely inside `setupData` (the Koin/DB-bound thread), like
 * [org.example.project.core.websocket.OpenAmountBroadcastTest]. On Postgres the
 * `r_table` FOR UPDATE lock serializes the two paths; on H2 (test engine) row
 * locking holds the same invariant for this access pattern.
 */
class OrderBillLockOrderingConcurrencyTest {
    private val deviceId = "lock-order-test-device"

    private fun createTerminal() =
        transaction {
            Terminal.new {
                this.deviceId = this@OrderBillLockOrderingConcurrencyTest.deviceId
                this.clientId = "LO-CLIENT"
                this.isActive = true
                this.registeredAt = currentTimeStamp()
                this.lastSeenAt = currentTimeStamp()
            }
        }

    /** Adds an OPEN item with the given finalPrice to a session; returns its id. */
    private fun addOpenItem(
        sessionId: Long,
        productId: Long,
        vatId: String,
        price: String,
    ): Long =
        transaction {
            OrderItem
                .new {
                    this.session = TableSession.findById(sessionId)!!
                    this.product = Product.findById(productId)!!
                    this.unitPrice = BigDecimal(price)
                    this.finalPrice = BigDecimal(price)
                    this.itemStatus = ItemStatus.OPEN
                    this.vat = Vat.findById(vatId)!!
                    this.discount = BigDecimal.ONE
                    this.customPrice = false
                    this.takeAway = false
                    this.transferred = false
                    this.sideNotes = null
                }.id.value
        }

    @Test
    fun `concurrent bill-completion and order-creation never leave a completed session with open items`() =
        testApp(
            setupData = {
                val manager = TestData.createManager(employeeNumber = "LO_MGR", username = "lo_mgr")
                val category = TestData.createCategory(name = "LO_Cat")
                val product =
                    TestData.createProduct(
                        categoryId = category.id.value,
                        productName = "LO_Prod",
                        productNumber = "LO_P",
                        price = BigDecimal("5.0000"),
                    )
                val vat = TestData.createDefaultVatRates().first()
                val cash = TestData.createPaymentType(name = "LO_Cash")
                createTerminal()

                val orderService = GlobalContext.get().get<OrderService>()
                val billService = GlobalContext.get().get<BillService>()
                val employeeId = manager.id.value.toString()

                val rounds = 12

                runBlocking {
                    repeat(rounds) { round ->
                        // Fresh table + active session, seeded with one OPEN item to bill.
                        val tws = TestData.createTableWithSession(tableNumber = "LO_$round")
                        val sessionId = tws.session.id.value
                        val tableId = tws.table.id.value
                        val billItemId = addOpenItem(sessionId, product.id.value, vat.id.value, "10.00")

                        // Bill the single OPEN item (would complete the session) while an order
                        // attaches a NEW OPEN item to the same table's active session.
                        coroutineScope {
                            val billTask =
                                async {
                                    runCatching {
                                        billService.createBill(
                                            employeeId = employeeId,
                                            deviceId = deviceId,
                                            request =
                                                BillDto.CreateBillRequestDto(
                                                    tableSessionId = sessionId,
                                                    orderItems = listOf(billItemId),
                                                    billAmount = "10.00",
                                                    payments =
                                                        listOf(
                                                            PaymentDto.PaymentRequest(amount = "10.00", paymentTypeId = cash.id.value),
                                                        ),
                                                ),
                                        )
                                    }
                                }
                            val orderTask =
                                async {
                                    runCatching {
                                        orderService.createOrder(
                                            request =
                                                OrderDto.CreateOrderRequestDto(
                                                    tableInfoId = sessionId,
                                                    tableId = tableId,
                                                    orderItems =
                                                        listOf(
                                                            OrderItemDto.CreateOrderItemRequestDto(
                                                                productId = product.id.value,
                                                                unitPrice = "5.00",
                                                                quantity = 1,
                                                            ),
                                                        ),
                                                ),
                                            employeeId = employeeId,
                                            deviceId = deviceId,
                                            actorCapabilities = emptySet(),
                                        )
                                    }
                                }
                            awaitAll(billTask, orderTask)
                        }
                    }
                }

                // INVARIANT: no completed session may retain an OPEN order item. This is the
                // exact corruption the lock-order fix prevents; it must hold no matter how any
                // round's bill/order pair interleaved.
                val offenders =
                    transaction {
                        OrderItem
                            .all()
                            .filter { it.itemStatus == ItemStatus.OPEN }
                            .filter { it.session.completed }
                            .map { it.id.value to it.session.id.value }
                    }
                assertTrue(
                    offenders.isEmpty(),
                    "Found OPEN items on completed sessions (itemId to sessionId): $offenders",
                )
            },
        ) { _ -> }
}
