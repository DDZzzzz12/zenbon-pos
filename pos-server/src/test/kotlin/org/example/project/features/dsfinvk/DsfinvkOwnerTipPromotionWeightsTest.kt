package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.bill.TipRecipient
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
import kotlin.test.assertNotNull

/**
 * Pins INV-F2 (round 3): the DSFinV-K export's OWNER-tip apportionment (`apportionOwnerTip`) must
 * weight the tip by the POST-promotion per-rate totals — exactly what `BillServiceImpl.createBill`
 * feeds `PromotionApportionment` when folding the tip into `BillVatDetail` (the authoritative
 * source of `transactions_vat`). Weighting the RAW `finalPrice` sums (the pre-fix behaviour)
 * diverged as soon as a bill combined a promotion + owner tip + ≥2 VAT rates: `lines_vat` and
 * `businesscases` drifted from the signed `transactions_vat` per (BON_ID, UST_SCHLUESSEL).
 *
 * Fixture: items 10.00 @19% and 10.00 @7%, an ITEM_SPECIFIC promotion −5.00 on the 19% item, a
 * 1.00 OWNER tip. `BillServiceImpl` post-promotion weights are {19%: 5.00, 7%: 10.00}; the 1.00 tip
 * apportioned over those by largest-remainder gives {19%: 0.33, 7%: 0.67}, so the authoritative
 * `BillVatDetail` brutto is {19%: 5.33, 7%: 10.67} (seeded below exactly as the service produces).
 *
 * With RAW weights {19%: 10, 7%: 10} the export would split the tip 0.50/0.50 → key-1 lines_vat
 * 10 − 5 + 0.50 = 5.50 vs transactions_vat 5.33 (FAIL). The fix makes the export reproduce the
 * post-promotion weights so both surfaces agree per key.
 */
@DisplayName("DSFinV-K export — owner-tip apportionment uses post-promotion weights (INV-F2)")
class DsfinvkOwnerTipPromotionWeightsTest {
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
    fun `owner tip over a promo + two VAT rates - lines_vat matches transactions_vat per key`() {
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val vats = TestData.createDefaultVatRates()
            val cash = TestData.createPaymentType("Cash", isCash = true)
            val tws = TestData.createTableWithSession("OTPW1")
            val category = TestData.createCategory("Owner Tip Promo Cat")
            val product = TestData.createProduct(category.id.value, "Owner Tip Promo Product", "OTP001")
            val now = currentTimeStamp()

            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-OTPW-TEST"
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
            val vat7 = vats[1]

            // Sale: 10.00 @19% and 10.00 @7% items, ITEM_SPECIFIC promo −5.00 on the 19% item,
            // 1.00 OWNER tip. paidAmount = Σ finalPrice(20.00) − promo(5.00) + tips(1.00) = 16.00.
            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "OTPW-A"
                    this.billAmount = BigDecimal("20.00")
                    this.promotionAmount = BigDecimal("5.00")
                    this.paidAmount = BigDecimal("16.00")
                    this.tips = BigDecimal("1.00")
                    this.tipRecipient = TipRecipient.OWNER
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }

            val item19 =
                OrderItem.new {
                    this.session = session
                    this.product = product
                    this.bill = bill
                    this.unitPrice = BigDecimal("10.00")
                    this.finalPrice = BigDecimal("10.00")
                    this.itemStatus = ItemStatus.PAID
                    this.vat = vat19
                    this.takeAway = false
                }

            OrderItem.new {
                this.session = session
                this.product = product
                this.bill = bill
                this.unitPrice = BigDecimal("10.00")
                this.finalPrice = BigDecimal("10.00")
                this.itemStatus = ItemStatus.PAID
                this.vat = vat7
                this.takeAway = false
            }

            val promotion =
                Promotion.new {
                    this.name = "5 EUR off the 19% item"
                    this.description = null
                    this.discountType = DiscountType.FIXED_AMOUNT
                    this.discountValue = BigDecimal("5.00")
                    this.scope = PromotionScope.ITEM_SPECIFIC
                    this.targetProduct = product
                    this.isMultiUse = false
                    this.validFrom = now
                    this.validUntil = null
                    this.isActive = true
                    this.createdBy = admin
                    this.createdAt = now
                }
            BillPromotion.new {
                this.bill = bill
                this.promotion = promotion
                this.orderItem = item19
                this.deductedAmount = BigDecimal("5.00")
                this.appliedBy = admin
                this.appliedAt = now
            }

            // Authoritative transactions_vat side: seed BillVatDetail EXACTLY as BillServiceImpl
            // produces it — post-promotion per-rate totals {19%: 5.00, 7%: 10.00} with the 1.00 tip
            // apportioned by largest-remainder {19%: 0.33, 7%: 0.67} → brutto {19%: 5.33, 7%: 10.67}.
            // net = total / (1 + rate) at scale 2 HALF_UP; vat = total − net (mirrors createBillVat).
            BillVatDetail.new {
                this.bill = bill
                this.vat = vat19
                this.totalAmount = BigDecimal("5.33")
                this.netAmount = BigDecimal("4.48") // 5.33 / 1.19
                this.vatAmount = BigDecimal("0.85")
            }
            BillVatDetail.new {
                this.bill = bill
                this.vat = vat7
                this.totalAmount = BigDecimal("10.67")
                this.netAmount = BigDecimal("9.97") // 10.67 / 1.07
                this.vatAmount = BigDecimal("0.70")
            }

            Payment.new {
                this.bill = bill
                this.paymentType = cash
                this.amount = BigDecimal("16.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }
        }

        val zip =
            runBlocking {
                service.generateExport(
                    DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"),
                )
            }

        val transactionsCsv = readZipEntry(zip, "transactions.csv")
        assertNotNull(transactionsCsv, "transactions.csv must be present in the export ZIP")
        val linesVatCsv = readZipEntry(zip, "lines_vat.csv")
        assertNotNull(linesVatCsv, "lines_vat.csv must be present in the export ZIP")
        val transactionsVatCsv = readZipEntry(zip, "transactions_vat.csv")
        assertNotNull(transactionsVatCsv, "transactions_vat.csv must be present in the export ZIP")

        val transactionRows = parseCsv(transactionsCsv)
        val linesVatRows = parseCsv(linesVatCsv)
        val transactionsVatRows = parseCsv(transactionsVatCsv)
        assertEquals(1, transactionRows.size, "fixture builds exactly one Beleg")

        val bonId = transactionRows.first()["BON_ID"]!!

        // Per (BON_ID, UST_SCHLUESSEL): Σ lines_vat.POS_BRUTTO must equal transactions_vat.BON_BRUTTO.
        // key 1 = 19% (expected 5.33), key 2 = 7% (expected 10.67).
        val expectedByKey = mapOf("1" to BigDecimal("5.33"), "2" to BigDecimal("10.67"))
        expectedByKey.forEach { (key, expected) ->
            val sumLinesVat =
                linesVatRows
                    .filter { it["BON_ID"] == bonId && it["UST_SCHLUESSEL"] == key }
                    .sumOf { parseAmount(it["POS_BRUTTO"]!!) }
            val sumTxVat =
                transactionsVatRows
                    .filter { it["BON_ID"] == bonId && it["UST_SCHLUESSEL"] == key }
                    .sumOf { parseAmount(it["BON_BRUTTO"]!!) }

            assertEquals(
                0,
                sumTxVat.compareTo(expected),
                "sanity: transactions_vat.BON_BRUTTO for key=$key must be $expected; got $sumTxVat",
            )
            assertEquals(
                0,
                sumLinesVat.compareTo(sumTxVat),
                "Σ(lines_vat.POS_BRUTTO) for (BON_ID=$bonId, key=$key) ($sumLinesVat) must equal " +
                    "transactions_vat.BON_BRUTTO ($sumTxVat)",
            )
        }
    }

    /**
     * An OWNER tip on a bill a promotion discounted to zero is still TrinkgeldAG — taxable at the
     * rate of the underlying supply (Anhang I p. 120), which a zero price does not change. Every
     * DSFinV-K surface must carry it at the item rate (key 1), and nothing may land in key 5, the
     * "Nicht steuerbar" key that belongs to an EMPLOYEE tip.
     *
     * Fixture: one 20.00 @19% item, a 20.00 FIXED_AMOUNT bill-wide promotion, a 3.00 OWNER tip,
     * paid 3.00. `BillVatDetail` is seeded as the service produces it: {19%: 3.00 = 2.52 + 0.48}.
     */
    @Test
    fun `owner tip on a fully discounted bill - carried at the item rate on every surface, never key 5`() {
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val vats = TestData.createDefaultVatRates()
            val cash = TestData.createPaymentType("Cash", isCash = true)
            val tws = TestData.createTableWithSession("OTFD1")
            val category = TestData.createCategory("Owner Tip Full Discount Cat")
            val product = TestData.createProduct(category.id.value, "Owner Tip Full Discount Product", "OTFD001")
            val now = currentTimeStamp()

            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-OTFD-TEST"
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

            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "OTFD-A"
                    this.billAmount = BigDecimal("20.00")
                    this.promotionAmount = BigDecimal("20.00")
                    this.paidAmount = BigDecimal("3.00")
                    this.tips = BigDecimal("3.00")
                    this.tipRecipient = TipRecipient.OWNER
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            OrderItem.new {
                this.session = session
                this.product = product
                this.bill = bill
                this.unitPrice = BigDecimal("20.00")
                this.finalPrice = BigDecimal("20.00")
                this.itemStatus = ItemStatus.PAID
                this.vat = vat19
                this.takeAway = false
            }
            val promotion =
                Promotion.new {
                    this.name = "20.00 off bill"
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
                this.bill = bill
                this.promotion = promotion
                this.orderItem = null
                this.deductedAmount = BigDecimal("20.00")
                this.appliedBy = admin
                this.appliedAt = now
            }
            BillVatDetail.new {
                this.bill = bill
                this.vat = vat19
                this.totalAmount = BigDecimal("3.00")
                this.netAmount = BigDecimal("2.52") // 3.00 / 1.19
                this.vatAmount = BigDecimal("0.48")
            }
            Payment.new {
                this.bill = bill
                this.paymentType = cash
                this.amount = BigDecimal("3.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }
        }

        val zip =
            runBlocking {
                service.generateExport(
                    DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"),
                )
            }

        val transactionRows = parseCsv(assertNotNull(readZipEntry(zip, "transactions.csv")))
        val linesVatRows = parseCsv(assertNotNull(readZipEntry(zip, "lines_vat.csv")))
        val transactionsVatRows = parseCsv(assertNotNull(readZipEntry(zip, "transactions_vat.csv")))
        val businessCaseRows = parseCsv(assertNotNull(readZipEntry(zip, "businesscases.csv")))
        assertEquals(1, transactionRows.size, "fixture builds exactly one Beleg")
        val bonId = transactionRows.first()["BON_ID"]!!

        val key1LinesVat =
            linesVatRows
                .filter { it["BON_ID"] == bonId && it["UST_SCHLUESSEL"] == "1" }
                .sumOf { parseAmount(it["POS_BRUTTO"]!!) }
        val key1TxVat =
            transactionsVatRows
                .filter { it["BON_ID"] == bonId && it["UST_SCHLUESSEL"] == "1" }
                .sumOf { parseAmount(it["BON_BRUTTO"]!!) }
        assertEquals(0, key1TxVat.compareTo(BigDecimal("3.00")), "sanity: transactions_vat key 1 carries the 3.00 tip; got $key1TxVat")
        assertEquals(
            0,
            key1LinesVat.compareTo(key1TxVat),
            "Σ lines_vat.POS_BRUTTO key 1 ($key1LinesVat) must equal transactions_vat.BON_BRUTTO ($key1TxVat): " +
                "the owner tip is taxed at the item rate even on a fully discounted bill",
        )
        val key5LinesVat = linesVatRows.filter { it["BON_ID"] == bonId && it["UST_SCHLUESSEL"] == "5" }
        assertEquals(emptyList(), key5LinesVat, "an owner tip must never be exported under key 5 (Nicht steuerbar)")

        val tipCases = businessCaseRows.filter { it["GV_TYP"] == DsfinvkExportDto.BusinessCaseTypes.TRINKGELD_AG }
        assertEquals(listOf("1"), tipCases.map { it["UST_SCHLUESSEL"] }, "TrinkgeldAG business case must be keyed 1; rows=$tipCases")
        assertEquals(0, parseAmount(tipCases.single()["Z_UMS_BRUTTO"]!!).compareTo(BigDecimal("3.00")), "TrinkgeldAG gross 3.00")
    }

    /**
     * Reads a single CSV entry (by exact basename) out of the export ZIP as text, or null.
     */
    private fun readZipEntry(
        zip: ByteArray,
        name: String,
    ): String? {
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.substringAfterLast('/') == name) return zis.readBytes().decodeToString()
                entry = zis.nextEntry
            }
        }
        return null
    }

    /** Parses a `;`-delimited, `"`-quoted DSFinV-K CSV into a list of header->value maps. */
    private fun parseCsv(csv: String): List<Map<String, String>> {
        val lines = csv.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()
        val header = splitCsvLine(lines.first())
        return lines.drop(1).map { line ->
            val fields = splitCsvLine(line)
            header.indices.associate { i -> header[i] to fields.getOrElse(i) { "" } }
        }
    }

    /** Quote-aware split of a `;`-delimited DSFinV-K CSV row. */
    private fun splitCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ';' && !inQuotes -> {
                    fields.add(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        fields.add(current.toString())
        return fields
    }

    /** Numeric DSFinV-K fields use a comma decimal separator. */
    private fun parseAmount(value: String): BigDecimal = BigDecimal(value.replace(",", "."))
}
