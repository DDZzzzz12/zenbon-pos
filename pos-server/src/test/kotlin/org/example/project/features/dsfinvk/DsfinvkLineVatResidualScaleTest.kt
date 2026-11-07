package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.models.BillVatDetail
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.table.domain.models.TableSession
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
 * Round-7 C6 — regression from round-5 BPOS-1.
 *
 * `applyLineVatGroupResidual` derives its per-rate target from `Σ row.gross`, where the ITEM row's
 * gross is the RAW `chargedUnitPrice` (`unit_price` is `decimal(19,4)`) while the Rabatt row's gross
 * comes from the scale-2 `discountRabattAmount` helper. BPOS-1 correctly unified the Rabatt
 * *predicate* through that helper, but in doing so it broke the telescoping the residual pass relied
 * on: at the parent commit both operands were raw, so `grossSum` collapsed exactly to `finalPrice`.
 * With one scale-2 and one scale-4 operand the target is derived from a sum that is neither
 * `Σ POS_BRUTTO` (what the file actually emits, each row rounded to 2 by `roundVatTriple`) nor
 * `BillVatDetail.totalAmount` (what `transactions_vat` emits) — so `Σ lines_vat` drifts a cent from
 * `transactions_vat` for the rate.
 *
 * DSFinV-K Tz. 3.1.2 (p. 19): „Aus den Positionsdaten müssen die Daten des Bonkopfes ermittelt und
 * insbesondere die Aufteilung des Gesamtumsatzes auf die unterschiedlichen Steuersätze
 * nachvollzogen werden können." A Σ that does not reproduce the Bonkopf breaks that Verprobung.
 *
 * Fixture (a sub-cent product price is reachable through the API today — `ProductDto.validate()`
 * only range-checks `price.toDouble()`, `ProductServiceImpl` persists `request.price.toBigDecimal()`
 * unclamped into `decimal(19,4)`, and `OrderServiceImpl` assigns `unitPrice = product.price` raw):
 * unitPrice 4.9010, discount 0.85 → finalPrice = round2(4.9010 × 0.85) = 4.17, 19 %.
 *
 *  - item row   gross 4.9010 → POS_BRUTTO 4,90, net round(4.9010/1.19,2) = 4,12
 *  - Rabatt row gross −(round2(4.9010) − round2(4.17)) = −0,73, net 3,50 − 4,12 = −0,62
 *  - Σ POS_BRUTTO = 4,17 == `transactions_vat.BON_BRUTTO`
 *  - target must therefore be round(4.17/1.19, 2) = 3,50 == `BillVatDetail` net == BON_NETTO
 *
 * Before the fix the pass computed `grossSum` = 4.9010 − 0.73 = 4.1710 → target 3,51, pushed the
 * spare cent onto the Rabatt row (−0,61 / −0,12) and Σ POS_NETTO came out 3,51 against BON_NETTO
 * 3,50.
 */
@DisplayName("DSFinV-K lines_vat group residual — mixed-scale gross sum drifts a cent from transactions_vat (C6)")
class DsfinvkLineVatResidualScaleTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    private fun export(): ByteArray =
        runBlocking {
            service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
        }

    @Test
    fun `sub-cent unit price with a discount keeps the lines_vat sum on the Bonkopf target`() {
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val cash = TestData.createPaymentType("Cash", isCash = true)
            val tws = TestData.createTableWithSession("C6TBL")
            val category = TestData.createCategory("C6 Cat")
            val product = TestData.createProduct(category.id.value, "C6 Product", "C6P001")
            val zBon = TestData.createZBonFullDay(shop, admin)
            val session = TableSession.findById(tws.session.id)!!
            val now = currentTimeStamp()

            val vat19 =
                Vat.findById("A") ?: Vat.new("A") {
                    this.vatClass = "Normal"
                    this.vatRate = BigDecimal("0.1900")
                }

            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "C6-BILL"
                    this.billAmount = BigDecimal("4.17")
                    this.paidAmount = BigDecimal("4.17")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }

            // transactions_vat is sourced from BillVatDetail — this is exactly what
            // BillServiceImpl.createBillVat stores for a 4,17 gross at 19 %: a SINGLE netting.
            BillVatDetail.new {
                this.bill = bill
                this.vat = vat19
                this.netAmount = BigDecimal("4.17").divide(BigDecimal("1.19"), 2, java.math.RoundingMode.HALF_UP)
                this.vatAmount = BigDecimal("4.17") - BigDecimal("4.17").divide(BigDecimal("1.19"), 2, java.math.RoundingMode.HALF_UP)
                this.totalAmount = BigDecimal("4.17")
            }

            OrderItem.new {
                this.session = session
                this.bill = bill
                this.product = product
                // decimal(19,4): a sub-cent price survives the round trip untouched.
                this.unitPrice = BigDecimal("4.9010")
                this.finalPrice = BigDecimal("4.17") // OrderServiceImpl rounds finalPrice to 2dp
                this.discount = BigDecimal("0.85")
                this.vat = vat19
                this.itemStatus = ItemStatus.PAID
                this.takeAway = false
            }

            Payment.new {
                this.bill = bill
                this.paymentType = cash
                this.amount = BigDecimal("4.17")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }
        }

        val zip = export()

        val bonId =
            readRows(zip, "transactions.csv")
                .single { it["BON_NR"] == "C6-BILL" || it["UMS_BRUTTO"] == "4,17" }["BON_ID"]!!

        val lineRows =
            readRows(zip, "lines_vat.csv")
                .filter { it["BON_ID"] == bonId && it["UST_SCHLUESSEL"] == "1" }
        val sumBrutto = lineRows.fold(BigDecimal.ZERO) { a, r -> a + dec(r["POS_BRUTTO"]!!) }
        val sumNetto = lineRows.fold(BigDecimal.ZERO) { a, r -> a + dec(r["POS_NETTO"]!!) }
        val sumUst = lineRows.fold(BigDecimal.ZERO) { a, r -> a + dec(r["POS_UST"]!!) }

        val bonVat =
            readRows(zip, "transactions_vat.csv")
                .single { it["BON_ID"] == bonId && it["UST_SCHLUESSEL"] == "1" }

        assertEquals(
            0,
            sumBrutto.compareTo(BigDecimal("4.17")),
            "Σ lines_vat POS_BRUTTO must reproduce the Bonkopf gross 4,17; got $sumBrutto (rows: $lineRows)",
        )
        assertEquals(
            0,
            sumNetto.compareTo(BigDecimal("3.50")),
            "Σ lines_vat POS_NETTO must be the single-netted round(4.17/1.19,2)=3,50, not 3,51 " +
                "(the residual pass must derive its target from the ROUNDED grosses it actually emits); got $sumNetto",
        )
        assertEquals(
            0,
            sumUst.compareTo(BigDecimal("0.67")),
            "Σ lines_vat POS_UST must be 4,17 − 3,50 = 0,67; got $sumUst",
        )
        assertEquals(
            0,
            dec(bonVat["BON_NETTO"]!!).compareTo(sumNetto),
            "Tz. 3.1.2: the Bonkopf VAT split must be derivable from the positions — " +
                "BON_NETTO ${bonVat["BON_NETTO"]} vs Σ POS_NETTO $sumNetto",
        )
        assertEquals(
            0,
            dec(bonVat["BON_UST"]!!).compareTo(sumUst),
            "BON_UST ${bonVat["BON_UST"]} must equal Σ POS_UST $sumUst",
        )
    }

    private fun dec(s: String): BigDecimal = BigDecimal(s.replace(",", "."))

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
