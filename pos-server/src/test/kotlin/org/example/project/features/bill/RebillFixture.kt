package org.example.project.features.bill

import kotlinx.coroutines.runBlocking
import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.order.OrderStatus
import org.example.project.features.bill.domain.service.BillService
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.database.tables.OrderItemTable
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.product.domain.models.Product
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.table.domain.models.TableSession
import org.example.project.testutils.TestData
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.core.context.GlobalContext
import java.math.BigDecimal

/**
 * One real table, billed through the REAL bill service, for the re-bill tests: ordered lines,
 * partial bills, "re-open the table" Stornos (`restoreSession = true`) and the bills that settle the
 * handed-back copies. Must be used inside a `testApp` block (Koin + the test database).
 *
 * Lines are ordered through an [Orders] parent on purpose — a re-opening Storno hands back only
 * lines that came from an order (or were themselves handed back), exactly as in production.
 */
class RebillFixture(
    private val deviceId: String,
    private val tag: String,
) {
    lateinit var managerId: String
        private set
    var sessionId: Long = 0
        private set
    private var cashTypeId: Long = 0
    private var cardTypeId: Long = 0
    private var productId: Long = 0
    private var vatId: String = ""
    private var orderSeq = 0

    private fun billService(): BillService = GlobalContext.get().get<BillService>()

    /** Shop, staff, tender, product, a fiscal terminal and the table with its open session. */
    fun seed(): RebillFixture {
        transaction { ShopInfo.all().firstOrNull() } ?: TestData.createShopInfo()
        vatId =
            TestData
                .createDefaultVatRates()
                .first()
                .id.value
        managerId =
            TestData
                .createManager(employeeNumber = "${tag}_MGR", username = "${tag.lowercase()}_mgr")
                .id.value
                .toString()
        cashTypeId = TestData.createPaymentType(name = "${tag}_Bar", isCash = true).id.value
        cardTypeId = TestData.createPaymentType(name = "${tag}_EC", isCash = false).id.value
        val category = TestData.createCategory(name = "${tag}_Cat")
        productId = TestData.createProduct(categoryId = category.id.value, productName = "${tag}_Food", productNumber = "${tag}_F").id.value
        TestData.createFiscalRegisteredTerminal(deviceId = deviceId, clientId = "$tag-CLIENT")
        sessionId =
            TestData
                .createTableWithSession(tableNumber = "${tag}_T")
                .session.id.value
        return this
    }

    /** Orders one line per price on the table. Returns the new lines' ids. */
    fun order(vararg prices: String): List<Long> =
        transaction {
            val session = TableSession.findById(sessionId)!!
            val order =
                Orders.new {
                    this.orderNumber = "ORD-$tag-${++orderSeq}"
                    this.employee = Employee.findById(java.util.UUID.fromString(managerId))!!
                    this.tableSession = session
                    this.orderTime = currentTimeStamp()
                    this.orderTotal = prices.fold(BigDecimal.ZERO) { acc, p -> acc + BigDecimal(p) }
                    this.orderStatus = OrderStatus.CONFIRMED
                }
            prices.map { price ->
                OrderItem
                    .new {
                        this.order = order
                        this.session = session
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
        }

    /** Bills [itemIds] in cash, for exactly their total. Returns the bill id. */
    fun bill(itemIds: List<Long>): Long {
        val total =
            transaction {
                itemIds.fold(BigDecimal.ZERO) { acc, id -> acc + OrderItem.findById(id)!!.finalPrice }
            }.setScale(2).toPlainString()
        return runBlocking {
            billService()
                .createBill(
                    employeeId = managerId,
                    deviceId = deviceId,
                    request =
                        BillDto.CreateBillRequestDto(
                            tableSessionId = sessionId,
                            orderItems = itemIds,
                            billAmount = total,
                            payments = listOf(PaymentDto.PaymentRequest(amount = total, paymentTypeId = cashTypeId)),
                        ),
                ).billId
        }
    }

    /** Storno with "Tisch zur Neuabrechnung wieder öffnen": the items come back to the table. */
    fun stornoReopen(billId: Long) =
        runBlocking {
            billService().deleteBill(
                employeeId = managerId,
                deviceId = deviceId,
                billId = billId.toString(),
                deletingReason = "Falsch gebucht",
                restoreSession = true,
            )
        }

    /** Zahlartwechsel of [billId] (paid [amount] in cash) to card, through the real service. Returns the Neubuchung. */
    fun rebookToCard(
        billId: Long,
        amount: String,
    ): BillDto.BillResponseDto =
        runBlocking {
            val role = transaction { Employee.findById(java.util.UUID.fromString(managerId))!!.role }
            billService().rebookBill(
                managerId,
                deviceId,
                billId.toString(),
                BillDto.RebookRequestDto(payments = listOf(PaymentDto.PaymentRequest(amount = amount, paymentTypeId = cardTypeId))),
                CapabilityBundles.forRole(role),
            )
        }

    /** The open, unbilled copies a re-opening Storno of [originalId] handed back, by id. */
    fun handedBackFrom(originalId: Long): List<Long> =
        transaction {
            OrderItem
                .find {
                    (OrderItemTable.duplicatedFromBillId eq originalId) and OrderItemTable.billId.isNull()
                }.map { it.id.value }
                .sorted()
        }
}
