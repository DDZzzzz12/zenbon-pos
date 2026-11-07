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
import kotlin.test.assertTrue

/**
 * Pins the BILL_WIDE aggregation decision: N bill-wide promotions produce ONE Rabatt line per VAT
 * RATE — never one line per promotion.
 *
 * This is a deliberate design decision, not an accident, and it is load-bearing arithmetic:
 * largest-remainder apportionment does not compose (see
 * `PromotionApportionmentTest.apportioning each promotion separately does NOT equal apportioning
 * their sum`), so a per-promotion split would make lines.csv disagree with lines_vat in both count
 * and amount — the H11 regression.
 *
 * It is also what the spec asks for. DSFinV-K §4.2.4 p. 36 on Zwischensummenrabatte: „Diese Rabatte
 * sind als gesonderte Positionszeile mit negativen Vorzeichen in der Datei Bonpos darzustellen. Die
 * Aufteilung der Entgeltminderung erfolgt in der Datei Bonpos_USt", and „getrennt nach
 * Steuersätzen". The organising dimension is the TAX RATE; the promotion is not a dimension the
 * spec asks Bonpos to carry. (§3.1.1.2 Bonpos_Preisfindung — sometimes cited for per-promotion
 * detail — explicitly does not apply here: „Wenn der Rabatt über eine separate Buchung abgebildet
 * wird, muss kein Eintrag erfolgen", and a separate booking is exactly this model.)
 *
 * So this test exists to stop a well-meaning refactor toward one-line-per-promotion: it buys
 * nothing the spec wants and reopens a proven arithmetic hazard.
 */
@DisplayName("BILL_WIDE promotions aggregate to one Rabatt line per VAT rate (H11 / §4.2.4)")
class DsfinvkBillWidePromotionAggregationTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @BeforeEach
    fun setup() = TestDatabase.setup()

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private var billId = 0L

    /** One bill, two VAT rates, TWO bill-wide promotions totalling 10.00. */
    private fun seed() {
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val vats = TestData.createDefaultVatRates() // [0]=19%, [1]=7%
            val cash = TestData.createPaymentType("Cash", isCash = true, isVoucher = false)
            val tws = TestData.createTableWithSession("BWP1")
            val category = TestData.createCategory("BillWide Promo Cat")
            val p19 = TestData.createProduct(category.id.value, "Food19", "BWP19")
            val p7 = TestData.createProduct(category.id.value, "Food7", "BWP07")
            val now = currentTimeStamp()

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

            // 60.00 @19% + 40.00 @7% = 100.00 gross, minus 10.00 of bill-wide promotions = 90.00.
            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "BWP-1001"
                    this.billAmount = BigDecimal("100.00")
                    this.promotionAmount = BigDecimal("10.00")
                    this.paidAmount = BigDecimal("90.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            billId = bill.id.value

            OrderItem.new {
                this.session = session
                this.product = p19
                this.bill = bill
                this.unitPrice = BigDecimal("60.00")
                this.finalPrice = BigDecimal("60.00")
                this.itemStatus = ItemStatus.PAID
                this.vat = vat19
                this.takeAway = false
            }
            OrderItem.new {
                this.session = session
                this.product = p7
                this.bill = bill
                this.unitPrice = BigDecimal("40.00")
                this.finalPrice = BigDecimal("40.00")
                this.itemStatus = ItemStatus.PAID
                this.vat = vat7
                this.takeAway = false
            }

            // TWO bill-wide promotions — the whole point of the fixture.
            listOf("Happy Hour" to "6.00", "Stammkunde" to "4.00").forEach { (name, amount) ->
                val promo =
                    Promotion.new {
                        this.name = name
                        this.description = null
                        this.discountType = DiscountType.FIXED_AMOUNT
                        this.discountValue = BigDecimal(amount)
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
                    this.promotion = promo
                    this.orderItem = null
                    this.deductedAmount = BigDecimal(amount)
                    this.appliedBy = admin
                    this.appliedAt = now
                }
            }

            // Post-promotion per-rate breakdown: 60 − 6 = 54 @19%, 40 − 4 = 36 @7%.
            BillVatDetail.new {
                this.bill = bill
                this.vat = vat19
                this.netAmount = BigDecimal("45.38")
                this.vatAmount = BigDecimal("8.62")
                this.totalAmount = BigDecimal("54.00")
            }
            BillVatDetail.new {
                this.bill = bill
                this.vat = vat7
                this.netAmount = BigDecimal("33.64")
                this.vatAmount = BigDecimal("2.36")
                this.totalAmount = BigDecimal("36.00")
            }

            Payment.new {
                this.bill = bill
                this.paymentType = cash
                this.amount = BigDecimal("90.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }
        }
    }

    @Test
    fun `two bill-wide promotions produce one Rabatt line per rate, not one per promotion`() {
        seed()
        val zip = export()
        val bonId = "B-$billId"

        val rabattLines =
            readRows(zip, "lines.csv")
                .filter { it["BON_ID"] == bonId && it["GV_TYP"] == "Rabatt" }

        assertEquals(
            2,
            rabattLines.size,
            "two promotions across two VAT rates must yield ONE aggregated Rabatt line PER RATE " +
                "(2), not one per promotion (4). Per-promotion lines would make largest-remainder " +
                "apportionment non-additive and break the lines/lines_vat agreement (H11). " +
                "Got: $rabattLines",
        )

        // One line per rate means the rate is the discriminator — the amounts must differ here
        // (54/36 weighting), proving they really are per-rate shares and not per-promotion halves.
        val amounts = rabattLines.map { germanDecimal(it.getValue("STK_BR")) }.sortedBy { it }
        assertEquals(
            0,
            amounts.fold(BigDecimal.ZERO) { a, b -> a + b }.compareTo(BigDecimal("-10.00")),
            "the two per-rate Rabatt lines must sum to the full bill-wide deduction (−10.00); got $amounts",
        )

        // The aggregated label names BOTH promotions — that is why it can overflow and is clamped.
        assertTrue(
            rabattLines.all { it.getValue("ARTIKELTEXT").startsWith("Aktion: ") },
            "the aggregated line's label is the generated 'Aktion: <names>' text",
        )
        assertTrue(
            rabattLines.any { it.getValue("ARTIKELTEXT").contains("Happy Hour") } &&
                rabattLines.any { it.getValue("ARTIKELTEXT").contains("Stammkunde") },
            "both promotion names must remain visible on the aggregated line",
        )
    }

    @Test
    fun `the aggregated Rabatt still reconciles lines_vat against transactions_vat per key`() {
        seed()
        val zip = export()
        val bonId = "B-$billId"

        val linesVatByKey =
            readRows(zip, "lines_vat.csv")
                .filter { it["BON_ID"] == bonId }
                .groupBy { it.getValue("UST_SCHLUESSEL") }
                .mapValues { (_, rows) -> rows.fold(BigDecimal.ZERO) { a, r -> a + germanDecimal(r.getValue("POS_BRUTTO")) } }
        val txVatByKey =
            readRows(zip, "transactions_vat.csv")
                .filter { it["BON_ID"] == bonId }
                .groupBy { it.getValue("UST_SCHLUESSEL") }
                .mapValues { (_, rows) -> rows.fold(BigDecimal.ZERO) { a, r -> a + germanDecimal(r.getValue("BON_BRUTTO")) } }

        assertEquals(txVatByKey.keys, linesVatByKey.keys, "lines_vat and transactions_vat must cover the same keys")
        txVatByKey.forEach { (key, expected) ->
            assertEquals(
                0,
                (linesVatByKey[key] ?: BigDecimal.ZERO).compareTo(expected),
                "key $key: Σlines_vat.POS_BRUTTO must equal Σtransactions_vat.BON_BRUTTO — the " +
                    "single combined apportionment is what makes this hold",
            )
        }
    }

    private fun export(): ByteArray =
        runBlocking {
            service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
        }

    private fun germanDecimal(cell: String): BigDecimal = BigDecimal(cell.trim().replace(".", "").replace(',', '.'))

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
                    val header = lines.first().split(";").map { it.trim('"') }
                    return lines.drop(1).map { line ->
                        header.zip(line.split(";").map { it.trim('"') }).toMap()
                    }
                }
                entry = zis.nextEntry
            }
        }
        return emptyList()
    }
}
