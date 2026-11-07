package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.promotion.DiscountType
import org.example.project.core.domain.enums.promotion.PromotionScope
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.models.BillVatDetail
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.promotion.domain.models.BillPromotion
import org.example.project.features.promotion.domain.models.Promotion
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
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

/**
 * Bonpos-level twin of round-3 KABS-2 (KABS-2b). A storno reverse bill re-emits the ORIGINAL's
 * items (negated via resolveOrderItems) but carries NO BillPromotion rows of its own. The
 * per-Beleg lines.csv / lines_vat.csv producers must therefore resolve promotions on the
 * original bill (via sourceBillId, like the voucher block) so the reversing Rabatt line is
 * emitted — otherwise Σ lines(MENGE×STK_BR) for the reverse bill is off by the promotion amount
 * vs. its transactions.csv UMS_BRUTTO, and lines_vat likewise doesn't net per (BON_ID, key).
 */
@DisplayName("DSFinV-K promotion — storno lines/lines_vat reverse the Rabatt line (KABS-2b)")
class DsfinvkStornoPromotionLinesTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    private var reverseBillId = 0L

    private fun seed() {
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val vats = TestData.createDefaultVatRates()
            val cash = TestData.createPaymentType("Cash", isCash = true, isVoucher = false)
            val tws = TestData.createTableWithSession("SPL1")
            val category = TestData.createCategory("Storno Promo Lines Cat")
            val product = TestData.createProduct(category.id.value, "Storno Promo Lines Product", "SPL001")
            val now = currentTimeStamp()

            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-SPL-TEST"
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
            val vat19 = vats[0]

            // Sale: 100.00 item, BILL_WIDE promotion deducting 20.00, 80.00 cash.
            val originalBill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "SPL-1001"
                    this.billAmount = BigDecimal("100.00")
                    this.promotionAmount = BigDecimal("20.00")
                    this.paidAmount = BigDecimal("80.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            // Reverse bill: all amounts negated. Carries NO BillPromotion row.
            val reverseBill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "SPL-2002"
                    this.billAmount = BigDecimal("-100.00")
                    this.promotionAmount = BigDecimal("-20.00")
                    this.paidAmount = BigDecimal("-80.00")
                    this.tips = BigDecimal.ZERO
                    this.deletedBill = originalBill
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            reverseBillId = reverseBill.id.value

            OrderItem.new {
                this.session = session
                this.product = product
                this.bill = originalBill
                this.unitPrice = BigDecimal("100.00")
                this.finalPrice = BigDecimal("100.00")
                this.itemStatus = ItemStatus.PAID
                this.vat = vat19
                this.takeAway = false
            }

            // Post-promo VAT breakdown on the ORIGINAL bill (100.00 item − 20.00 bill-wide promo =
            // 80.00 gross @ 19%). transactions_vat sources this via sourceBillId; the reverse bill
            // has none of its own.
            BillVatDetail.new {
                this.bill = originalBill
                this.vat = vat19
                this.netAmount = BigDecimal("67.23")
                this.vatAmount = BigDecimal("12.77")
                this.totalAmount = BigDecimal("80.00")
            }

            Payment.new {
                this.bill = originalBill
                this.paymentType = cash
                this.amount = BigDecimal("80.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.REFUNDED
                this.paymentTime = now
                this.refundTime = now
                this.refundedBillId = reverseBill
            }

            val promotion =
                Promotion.new {
                    this.name = "20 EUR off bill"
                    this.description = null
                    this.discountType = DiscountType.FIXED_AMOUNT
                    this.discountValue = BigDecimal("20.00")
                    this.scope = PromotionScope.BILL_WIDE
                    this.targetProduct = null
                    this.isMultiUse = false
                    this.validFrom = now
                    this.validUntil = null
                    this.isActive = true
                    this.createdBy = admin
                    this.createdAt = now
                }
            BillPromotion.new {
                this.bill = originalBill
                this.promotion = promotion
                this.orderItem = null
                this.deductedAmount = BigDecimal("20.00")
                this.appliedBy = admin
                this.appliedAt = now
            }
        }
    }

    private fun export(): ByteArray =
        runBlocking {
            service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
        }

    /** Parse a German-formatted decimal cell (thousands '.', decimal ','). */
    private fun germanDecimal(cell: String): BigDecimal = BigDecimal(cell.trim().replace(".", "").replace(',', '.'))

    @Test
    fun `storno reverse bill lines sum to its UMS_BRUTTO and lines_vat nets per key`() {
        seed()
        val zip = export()
        val bonId = "B-$reverseBillId"

        // Σ lines(MENGE × STK_BR) for the reverse bill == its transactions.csv UMS_BRUTTO.
        val lines = readRows(zip, "lines.csv").filter { it["BON_ID"] == bonId }
        val lineSum =
            lines.fold(BigDecimal.ZERO) { acc, row ->
                acc + germanDecimal(row.getValue("MENGE")) * germanDecimal(row.getValue("STK_BR"))
            }
        val umsBrutto =
            germanDecimal(
                readRows(zip, "transactions.csv").single { it["BON_ID"] == bonId }.getValue("UMS_BRUTTO"),
            )
        assertEquals(
            0,
            lineSum.compareTo(umsBrutto),
            "Σ lines(MENGE×STK_BR)=$lineSum must equal UMS_BRUTTO=$umsBrutto for $bonId; lines=$lines",
        )

        // lines_vat.csv per-key POS_BRUTTO sum == transactions_vat.csv per-key BON_BRUTTO.
        val linesVatByKey =
            readRows(zip, "lines_vat.csv")
                .filter { it["BON_ID"] == bonId }
                .groupBy { it.getValue("UST_SCHLUESSEL") }
                .mapValues { (_, rows) -> rows.fold(BigDecimal.ZERO) { acc, r -> acc + germanDecimal(r.getValue("POS_BRUTTO")) } }
        val txVatByKey =
            readRows(zip, "transactions_vat.csv")
                .filter { it["BON_ID"] == bonId }
                .groupBy { it.getValue("UST_SCHLUESSEL") }
                .mapValues { (_, rows) -> rows.fold(BigDecimal.ZERO) { acc, r -> acc + germanDecimal(r.getValue("BON_BRUTTO")) } }

        assertEquals(
            txVatByKey.keys,
            linesVatByKey.keys,
            "lines_vat keys must match transactions_vat keys for $bonId",
        )
        txVatByKey.forEach { (key, expected) ->
            val actual = linesVatByKey[key] ?: BigDecimal.ZERO
            assertEquals(
                0,
                actual.compareTo(expected),
                "lines_vat POS_BRUTTO=$actual must equal transactions_vat BON_BRUTTO=$expected for key $key on $bonId",
            )
        }
    }

    private fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    cur.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ';' && !inQuotes -> {
                    out.add(cur.toString())
                    cur.clear()
                }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out
    }

    private fun readRows(
        zip: ByteArray,
        name: String,
    ): List<Map<String, String>> {
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.substringAfterLast('/') == name) {
                    val lines =
                        zis
                            .readBytes()
                            .decodeToString()
                            .split("\r\n")
                            .filter { it.isNotBlank() }
                    if (lines.size < 2) return emptyList()
                    val header = splitCsvLine(lines.first())
                    return lines.drop(1).map { header.zip(splitCsvLine(it)).toMap() }
                }
                entry = zis.nextEntry
            }
        }
        error("$name not found in export zip")
    }
}
