package org.example.project.features.bill

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.order.OrderStatus
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.websocket.NotificationHub
import org.example.project.core.websocket.OutgoingFrame
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.product.domain.models.Product
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E2E-2026-09-18 #11: a storno re-opens the table's session (OCCUPIED, the items back on it), but
 * sent no table-status update — every floor plan kept showing the table "Frei" until a restart,
 * and the forgotten open session then blocked the Z-Bon.
 */
class StornoRestoresTableNotificationTest {
    private val deviceId = "storno-restore-device"

    @AfterEach
    fun tearDown() {
        NotificationHub.resetForTest()
        BillNumberTracker.clearAll()
    }

    private fun Channel<OutgoingFrame>.drainTableStatusUpdates(): List<NotificationDto.TableStatusUpdateDto> {
        val updates = mutableListOf<NotificationDto.TableStatusUpdateDto>()
        while (true) {
            val frame = tryReceive().getOrNull() ?: break
            val msg = (frame as? OutgoingFrame.Event)?.event?.message
            if (msg is NotificationDto.TableStatusUpdateDto) updates += msg
        }
        return updates
    }

    @Test
    fun `a storno that re-opens the session tells the floor plans the table is occupied`() =
        testApp(
            setupData = {
                val manager = TestData.createManager(employeeNumber = "SR_MGR", username = "sr_mgr")
                val category = TestData.createCategory(name = "SR_Cat")
                val product = TestData.createProduct(categoryId = category.id.value, productName = "SR_Prod", productNumber = "SR_P")
                val vat = TestData.createDefaultVatRates().first()
                val cash = TestData.createPaymentType(name = "SR_Cash")
                val tws = TestData.createTableWithSession(tableNumber = "SR_T")
                val itemId =
                    transaction {
                        Terminal.new {
                            this.deviceId = this@StornoRestoresTableNotificationTest.deviceId
                            this.clientId = "SR-CLIENT"
                            this.isActive = true
                            this.registeredAt = currentTimeStamp()
                            this.lastSeenAt = currentTimeStamp()
                        }
                        OrderItem
                            .new {
                                this.session = TableSession.findById(tws.session.id)!!
                                this.product = Product.findById(product.id)!!
                                this.unitPrice = BigDecimal("10.00")
                                this.finalPrice = BigDecimal("10.00")
                                this.itemStatus = ItemStatus.OPEN
                                this.vat = Vat.findById(vat.id)!!
                                this.discount = BigDecimal.ONE
                                this.customPrice = false
                                this.takeAway = false
                                this.transferred = false
                                this.sideNotes = null
                            }.id.value
                    }
                val service = GlobalContext.get().get<BillService>()
                // Paying the only item completes the session and frees the table.
                val bill =
                    runBlocking {
                        service.createBill(
                            employeeId = manager.id.value.toString(),
                            deviceId = deviceId,
                            request =
                                BillDto.CreateBillRequestDto(
                                    tableSessionId = tws.session.id.value,
                                    orderItems = listOf(itemId),
                                    billAmount = "10.00",
                                    payments = listOf(PaymentDto.PaymentRequest(amount = "10.00", paymentTypeId = cash.id.value)),
                                ),
                        )
                    }
                transaction { assertTrue(TableSession.findById(tws.session.id)!!.completed, "precondition: the bill closed the session") }

                val updates =
                    runBlocking {
                        val out = Channel<OutgoingFrame>(Channel.UNLIMITED)
                        NotificationHub.addSseConnection(connectionId = "obs", employeeId = "obs", outgoing = out)
                        service.deleteBill(
                            employeeId = manager.id.value.toString(),
                            deviceId = deviceId,
                            billId = bill.billId.toString(),
                            deletingReason = "Falsch gebucht",
                        )
                        out.drainTableStatusUpdates()
                    }

                val update = updates.single { it.tableId == tws.table.id.value }
                assertEquals(TableStatus.OCCUPIED.name, update.status.toString())
                assertEquals(tws.session.id.value, update.sessionId)
            },
        ) { _ -> }

    /**
     * emu18 NOTE-g: a partial bill leaves the session open; its Storno with the reopen switch on
     * hands the paid items back to that still-live session. The open amount grew, but only a
     * re-opened *completed* session was announced, so every card kept the old amount.
     */
    @Test
    fun `a storno that hands items back to a still-open session announces the new open amount`() =
        testApp(
            setupData = {
                val manager = TestData.createManager(employeeNumber = "SR_MGR2", username = "sr_mgr2")
                val category = TestData.createCategory(name = "SR_Cat2")
                val product = TestData.createProduct(categoryId = category.id.value, productName = "SR_Prod2", productNumber = "SR_P2")
                val vat = TestData.createDefaultVatRates().first()
                val cash = TestData.createPaymentType(name = "SR_Cash2")
                val tws = TestData.createTableWithSession(tableNumber = "SR_T2")
                val (paidItemId, openItemId) =
                    transaction {
                        Terminal.new {
                            this.deviceId = this@StornoRestoresTableNotificationTest.deviceId
                            this.clientId = "SR-CLIENT"
                            this.isActive = true
                            this.registeredAt = currentTimeStamp()
                            this.lastSeenAt = currentTimeStamp()
                        }
                        val session = TableSession.findById(tws.session.id)!!
                        // Only items with a parent order are handed back for re-billing.
                        val order =
                            Orders.new {
                                this.orderNumber = "ORD-SR2-1"
                                this.employee = Employee.findById(manager.id)!!
                                this.tableSession = session
                                this.orderTime = currentTimeStamp()
                                this.orderTotal = BigDecimal("8.9000")
                                this.orderStatus = OrderStatus.CONFIRMED
                            }

                        fun item(price: String) =
                            OrderItem
                                .new {
                                    this.order = order
                                    this.session = session
                                    this.product = Product.findById(product.id)!!
                                    this.unitPrice = BigDecimal(price)
                                    this.finalPrice = BigDecimal(price)
                                    this.itemStatus = ItemStatus.OPEN
                                    this.vat = Vat.findById(vat.id)!!
                                    this.discount = BigDecimal.ONE
                                    this.customPrice = false
                                    this.takeAway = false
                                    this.transferred = false
                                    this.sideNotes = null
                                }.id.value
                        item("6.00") to item("2.90")
                    }
                val service = GlobalContext.get().get<BillService>()
                // Paying one of two items leaves the session open (the partial bill RE-19 in emu18).
                val bill =
                    runBlocking {
                        service.createBill(
                            employeeId = manager.id.value.toString(),
                            deviceId = deviceId,
                            request =
                                BillDto.CreateBillRequestDto(
                                    tableSessionId = tws.session.id.value,
                                    orderItems = listOf(paidItemId),
                                    billAmount = "6.00",
                                    payments = listOf(PaymentDto.PaymentRequest(amount = "6.00", paymentTypeId = cash.id.value)),
                                ),
                        )
                    }
                transaction {
                    assertTrue(!TableSession.findById(tws.session.id)!!.completed, "precondition: the partial bill left the session open")
                    assertEquals(ItemStatus.OPEN, OrderItem.findById(openItemId)!!.itemStatus)
                }

                val updates =
                    runBlocking {
                        val out = Channel<OutgoingFrame>(Channel.UNLIMITED)
                        NotificationHub.addSseConnection(connectionId = "obs", employeeId = "obs", outgoing = out)
                        service.deleteBill(
                            employeeId = manager.id.value.toString(),
                            deviceId = deviceId,
                            billId = bill.billId.toString(),
                            deletingReason = "Falsch gebucht",
                            restoreSession = true,
                        )
                        out.drainTableStatusUpdates()
                    }

                val update = updates.single { it.tableId == tws.table.id.value }
                assertEquals(TableStatus.OCCUPIED.name, update.status.toString())
                assertEquals(tws.session.id.value, update.sessionId)
                assertEquals("8.90", update.openAmount)
            },
        ) { _ -> }
}
