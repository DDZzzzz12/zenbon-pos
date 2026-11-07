package org.example.project.features.bill

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.order.OrderStatus
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.domain.enums.voucher.VoucherStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.database.tables.OrderItemTable
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.product.domain.models.Product
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.voucher.domain.models.Voucher
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Storno re-opens the bill's completed table session and makes it the table's `currentSession`
 * again. If the table was freed after the sale and NEW guests now sit there, that overwrote their
 * session and hid it from the floor plan. Now a re-opening Storno is refused (409
 * TABLE_HAS_ACTIVE_SESSION) and the till can Storno without re-opening (`restoreSession = false`).
 */
class StornoRestoreSessionGuardTest {
    private val deviceId = "storno-guard-device"

    private class Fixture(
        val service: BillService,
        val managerId: String,
        val tableId: Long,
        val oldSessionId: Long,
        val billId: Long,
        val voucherId: Long,
    )

    @AfterEach
    fun tearDown() {
        BillNumberTracker.clearAll()
    }

    /** One ordered food line plus one sold voucher, paid in full: the session completes, the table frees. */
    private fun seedPaidTable(tag: String): Fixture {
        val manager = TestData.createManager(employeeNumber = "${tag}_MGR", username = "${tag.lowercase()}_mgr")
        val category = TestData.createCategory(name = "${tag}_Cat")
        val food = TestData.createProduct(categoryId = category.id.value, productName = "${tag}_Food", productNumber = "${tag}_F")
        val voucherArticle = TestData.createProduct(categoryId = category.id.value, productName = "Gutschein", productNumber = "VOU")
        val vat = TestData.createDefaultVatRates().first()
        val cash = TestData.createPaymentType(name = "${tag}_Cash")
        val tws = TestData.createTableWithSession(tableNumber = "${tag}_T")
        var voucherId = 0L
        val itemIds =
            transaction {
                Terminal.new {
                    this.deviceId = this@StornoRestoreSessionGuardTest.deviceId
                    this.clientId = "$tag-CLIENT"
                    this.isActive = true
                    this.registeredAt = currentTimeStamp()
                    this.lastSeenAt = currentTimeStamp()
                }
                val session = TableSession.findById(tws.session.id)!!
                // Only items with a parent order are duplicated for re-billing.
                val order =
                    Orders.new {
                        this.orderNumber = "ORD-$tag-1"
                        this.employee = Employee.findById(manager.id)!!
                        this.tableSession = session
                        this.orderTime = currentTimeStamp()
                        this.orderTotal = BigDecimal("20.0000")
                        this.orderStatus = OrderStatus.CONFIRMED
                    }

                fun line(product: Product): OrderItem =
                    OrderItem.new {
                        this.order = order
                        this.session = session
                        this.product = product
                        this.unitPrice = BigDecimal("10.00")
                        this.finalPrice = BigDecimal("10.00")
                        this.itemStatus = ItemStatus.OPEN
                        this.vat = Vat.findById(vat.id)!!
                        this.discount = BigDecimal.ONE
                        this.customPrice = false
                        this.takeAway = false
                        this.transferred = false
                        this.sideNotes = null
                    }
                val foodItem = line(Product.findById(food.id)!!)
                val voucherItem = line(Product.findById(voucherArticle.id)!!)
                voucherId =
                    Voucher
                        .new {
                            this.orderItem = voucherItem
                            this.voucherNumber = "G-$tag-0001"
                            this.originalValue = BigDecimal("10.00")
                            this.remainingValue = BigDecimal("10.00")
                            this.createdAt = currentTimeStamp()
                        }.id.value
                listOf(foodItem.id.value, voucherItem.id.value)
            }
        val service = GlobalContext.get().get<BillService>()
        val bill =
            runBlocking {
                service.createBill(
                    employeeId = manager.id.value.toString(),
                    deviceId = deviceId,
                    request =
                        BillDto.CreateBillRequestDto(
                            tableSessionId = tws.session.id.value,
                            orderItems = itemIds,
                            billAmount = "20.00",
                            payments = listOf(PaymentDto.PaymentRequest(amount = "20.00", paymentTypeId = cash.id.value)),
                        ),
                )
            }
        transaction {
            assertTrue(TableSession.findById(tws.session.id)!!.completed, "precondition: the bill closed the session")
            assertNull(RTable.findById(tws.table.id)!!.currentSession, "precondition: the bill freed the table")
        }
        return Fixture(service, manager.id.value.toString(), tws.table.id.value, tws.session.id.value, bill.billId, voucherId)
    }

    /** New guests sit down at the freed table. Returns their session id. */
    private fun seatNewGuests(f: Fixture): Long =
        transaction {
            val waiter = TestData.createCashier(employeeNumber = "NEW_${f.tableId}", username = "new_${f.tableId}")
            RTable
                .findById(f.tableId)!!
                .startSession(Employee.findById(waiter.id)!!)
                .id.value
        }

    private fun Fixture.storno(restoreSession: Boolean) =
        runBlocking {
            service.deleteBill(
                employeeId = managerId,
                deviceId = deviceId,
                billId = billId.toString(),
                deletingReason = "Falsch gebucht",
                restoreSession = restoreSession,
            )
        }

    private fun duplicatedItemCount(): Long =
        transaction {
            OrderItem.find { OrderItemTable.duplicatedFromBillId.isNotNull() }.count()
        }

    @Test
    fun `a re-opening storno while new guests sit at the table is refused and keeps their session current`() =
        testApp(
            setupData = {
                val f = seedPaidTable("SG1")
                val newSessionId = seatNewGuests(f)

                val ex = assertFailsWith<PosException> { f.storno(restoreSession = true) }
                assertEquals(PosErrorCode.TABLE_HAS_ACTIVE_SESSION, ex.code)
                assertEquals(HttpStatusCode.Conflict, ex.httpStatus)

                transaction {
                    val table = RTable.findById(f.tableId)!!
                    assertEquals(newSessionId, table.currentSession?.id?.value, "the new guests' session must stay current")
                    assertEquals(TableStatus.OCCUPIED, table.tableStatus)
                    assertTrue(TableSession.findById(f.oldSessionId)!!.completed, "the old session must stay closed")
                    assertNull(Bill.findById(f.billId)!!.deletingReason, "a refused storno writes nothing")
                    assertEquals(0L, Bill.find { BillTable.deletedBillId.isNotNull() }.count(), "no reverse bill")
                }
                assertEquals(0L, duplicatedItemCount())
            },
        ) { _ -> }

    @Test
    fun `a storno without re-opening while new guests sit at the table leaves their session current`() =
        testApp(
            setupData = {
                val f = seedPaidTable("SG2")
                val newSessionId = seatNewGuests(f)

                f.storno(restoreSession = false)

                transaction {
                    val table = RTable.findById(f.tableId)!!
                    assertEquals(newSessionId, table.currentSession?.id?.value, "the new guests' session must stay current")
                    assertEquals(TableStatus.OCCUPIED, table.tableStatus)
                    assertTrue(TableSession.findById(f.oldSessionId)!!.completed, "the old session must stay closed")
                    assertTrue(Bill.findById(f.billId)!!.deletingReason != null, "the bill is cancelled")
                    assertEquals(1L, Bill.find { BillTable.deletedBillId.isNotNull() }.count(), "one reverse bill")
                    assertEquals(
                        VoucherStatus.INVALIDATED,
                        Voucher.findById(f.voucherId)!!.status,
                        "a sold voucher that is not re-billed dies with the sale",
                    )
                }
                assertEquals(0L, duplicatedItemCount(), "nothing is copied for re-billing")
            },
        ) { _ -> }

    @Test
    fun `a re-opening storno on a free table still restores the session for re-billing`() =
        testApp(
            setupData = {
                val f = seedPaidTable("SG3")

                f.storno(restoreSession = true)

                transaction {
                    val table = RTable.findById(f.tableId)!!
                    assertEquals(f.oldSessionId, table.currentSession?.id?.value)
                    assertEquals(TableStatus.OCCUPIED, table.tableStatus)
                    assertFalse(TableSession.findById(f.oldSessionId)!!.completed)
                    val voucher = Voucher.findById(f.voucherId)!!
                    assertEquals(VoucherStatus.ACTIVE, voucher.status, "a re-billed voucher stays sellable")
                    assertTrue(voucher.orderItem.duplicatedFromBill != null, "the voucher follows its re-billed line")
                }
                assertEquals(2L, duplicatedItemCount())
            },
        ) { _ -> }
}
