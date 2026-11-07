package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.order.OrderStatus
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.models.BillVatDetail
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.order.domain.models.Cancellation
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.order.domain.models.Orders
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression test for the BON_ID collision defect (audit finding C5).
 *
 * Bills, Orders and Cancellations are three separate `LongIdTable`s, each with its own
 * auto-increment sequence starting at 1. The exporter used to emit each entity's raw PK as
 * `BON_ID` (`bill.id.value.toString()`, `order.id.value.toString()`, …), so Bill#1 (a Beleg),
 * Order#1 (an AVBestellung) and Cancellation#1 (a Storno) all emitted `BON_ID="1"` — colliding
 * in transactions.csv and making every parent/child join (lines, lines_vat, datapayment,
 * transactions_tse, references, …) ambiguous / double-counted. Only Kassenbuch was namespaced
 * (`KB-`).
 *
 * The fix namespaces each source: bills → `B-<id>`, orders → `O-<id>`, cancellations → `C-<id>`
 * (Kassenbuch keeps `KB-<id>`). This test forces the collision (all three ids = 1) and asserts
 * (a) every transactions.csv `BON_ID` is globally unique and (b) a child row (a bill line in
 * lines.csv) still joins to exactly one transactions.csv header.
 */
@DisplayName("DSFinV-K export — BON_ID namespace")
class DsfinvkBonIdNamespaceTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    @Test
    fun `bill, order and cancellation with colliding PKs get globally unique BON_IDs`() {
        var billId = 0L
        var orderId = 0L
        var cancellationId = 0L

        transaction {
            val admin = TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            val cash = TestData.createPaymentType("Cash", isCash = true)
            val vats = TestData.createDefaultVatRates() // [0]=19% Normal
            val vat19 = vats[0]
            val category = TestData.createCategory("NS Cat")
            val product = TestData.createProduct(category.id.value, "Cola", "COLA")
            val tws = TestData.createTableWithSession("NS1")
            val now = currentTimeStamp()

            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-NS-TEST"
                registeredAt = now
                lastSeenAt = now
            }

            val zBon =
                ZBon.new {
                    this.shopInfo = shop
                    this.employee = admin
                    this.startTime = now
                    this.endTime = now
                    this.cancelledAmount = BigDecimal.ZERO
                    this.totalDiscounts = BigDecimal.ZERO
                    this.totalPosRevenue = BigDecimal.ZERO
                    this.adminTip = BigDecimal.ZERO
                    this.openingBalance = BigDecimal.ZERO
                    this.expenses = BigDecimal.ZERO
                    this.cashRevenue = BigDecimal.ZERO
                    this.closingBalance = BigDecimal.ZERO
                    this.createdAt = now
                    this.trainingRevenue = BigDecimal.ZERO
                    this.trainingBillCount = 0
                    this.refundAmount = BigDecimal.ZERO
                    this.stornoBillCount = 0
                    this.zbonNummer = 1L
                }
            val session = TableSession.findById(tws.session.id)!!

            // (1) A bill (Beleg) with one line item so lines.csv carries a child row for it.
            val sale =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "NS-A"
                    this.billAmount = BigDecimal("12.00")
                    this.paidAmount = BigDecimal("12.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            BillVatDetail.new {
                this.bill = sale
                this.vat = vat19
                this.netAmount = BigDecimal("10.08")
                this.vatAmount = BigDecimal("1.92")
                this.totalAmount = BigDecimal("12.00")
            }
            OrderItem.new {
                this.session = session
                this.bill = sale
                this.product = product
                this.unitPrice = BigDecimal("12.00")
                this.finalPrice = BigDecimal("12.00")
                this.vat = vat19
                this.itemStatus = ItemStatus.PAID
                this.takeAway = false
            }
            Payment.new {
                this.bill = sale
                this.paymentType = cash
                this.amount = BigDecimal("12.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }

            // (2) An order (AVBestellung) — picked up by orderTime being in range.
            val order =
                Orders.new {
                    this.orderNumber = "NS-ORD-1"
                    this.employee = admin
                    this.tableSession = session
                    this.orderStatus = OrderStatus.CONFIRMED
                    this.orderTime = now
                    this.orderTotal = BigDecimal("5.00")
                    this.tseSigningStatus = TseSigningStatus.PENDING
                    this.isTraining = false
                }

            // (3) A cancellation (Storno) — references a standalone order item; kept in this ZBon.
            val cancelledItem =
                OrderItem.new {
                    this.session = session
                    this.product = product
                    this.unitPrice = BigDecimal("3.00")
                    this.finalPrice = BigDecimal("3.00")
                    this.vat = vat19
                    this.itemStatus = ItemStatus.CANCELLED
                    this.takeAway = false
                }
            val cancellation =
                Cancellation.new {
                    this.orderItem = cancelledItem
                    this.reason = "test storno"
                    this.employeeId = admin
                    this.createdAt = now
                    this.zBon = zBon
                    this.tseSigningStatus = TseSigningStatus.PENDING
                    this.isTraining = false
                }

            billId = sale.id.value
            orderId = order.id.value
            cancellationId = cancellation.id.value
        }

        // The collision precondition: all three live in separate tables, so each is the first
        // row of its table → id 1. Pre-fix, all three would emit BON_ID="1".
        assertEquals(1L, billId, "bill is the first row of its table")
        assertEquals(1L, orderId, "order is the first row of its table")
        assertEquals(1L, cancellationId, "cancellation is the first row of its table")

        val zip =
            runBlocking {
                service.generateExport(
                    DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"),
                )
            }

        val transactionsCsv = readZipEntry(zip, "transactions.csv")
        assertNotNull(transactionsCsv, "transactions.csv must be present in the export ZIP")

        // BON_ID is column index 3 in transactions.csv.
        val bonIds =
            dataRows(transactionsCsv)
                .map { it.split(";")[3].trim('"') }
                .toList()

        assertTrue(bonIds.isNotEmpty(), "transactions.csv must have data rows; got:\n$transactionsCsv")
        assertEquals(
            bonIds.size,
            bonIds.distinct().size,
            "BON_ID must be globally unique across bills/orders/cancellations; got $bonIds",
        )

        // All three namespaced parents must be present and distinct despite colliding raw PKs.
        assertTrue("B-1" in bonIds, "bill header must use B- prefix; got $bonIds")
        assertTrue("O-1" in bonIds, "order header must use O- prefix; got $bonIds")
        assertTrue("C-1" in bonIds, "cancellation header must use C- prefix; got $bonIds")

        // Join integrity: the bill's line row(s) in lines.csv must carry the bill's BON_ID and
        // that BON_ID must match exactly one transactions.csv header row.
        val linesCsv = readZipEntry(zip, "lines.csv")
        assertNotNull(linesCsv, "lines.csv must be present in the export ZIP")
        val lineBonIds = dataRows(linesCsv).map { it.split(";")[3].trim('"') }.toList()
        assertTrue("B-1" in lineBonIds, "bill line must join to the bill header B-1; got $lineBonIds")
        assertEquals(
            1,
            bonIds.count { it == "B-1" },
            "the bill line's BON_ID must resolve to exactly one transactions.csv header",
        )
    }

    /** Data rows (skip header) of a `\r\n`-terminated DSFinV-K CSV. */
    private fun dataRows(csv: String): List<String> = csv.split("\r\n").filter { it.isNotBlank() }.drop(1)

    /** Reads a single CSV entry (by filename suffix) out of the export ZIP as text, or null. */
    private fun readZipEntry(
        zip: ByteArray,
        name: String,
    ): String? {
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.endsWith(name)) return zis.readBytes().decodeToString()
                entry = zis.nextEntry
            }
        }
        return null
    }
}
