package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.order.domain.models.OrderItem
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
import kotlin.test.assertTrue

/**
 * BPOS-1 (audit round 4, 2026-07-17): lines.csv, lines_vat.csv and businesscases.csv each
 * carried their OWN Rabatt predicate — lines.csv rounded both operands before subtracting,
 * lines_vat compared the RAW difference, businesscases required `> 0`. A sub-cent charged
 * price (`OrderItemTable.unitPrice` is `decimal(19,4)`) whose discount delta collapses to
 * 0.00 after rounding made lines.csv skip the Rabatt row (and therefore never
 * `recordDiscount`) while lines_vat still asked [org.example.project.export.dsfinvk.builder.BonposLinePlan]
 * for that row's POS_ZEILE — `Map.getValue` → `NoSuchElementException` → escaped
 * `generateExport`'s catch → `fileGenerationFailed` → the WHOLE period produced no CSVs.
 *
 * All three producers must now decide through the single `discountRabattAmount` predicate.
 */
@DisplayName("Rabatt guard parity across lines/lines_vat/businesscases (BPOS-1)")
class DsfinvkDiscountGuardParityTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    /**
     * Seeds one bill with one discounted item. `billNumber` must be digit-unique per closing —
     * `sortTransactionsForClosing` aborts the export on duplicate BON_NR.
     */
    private fun seedBillWithItem(
        billNumber: String,
        unitPrice: String,
        finalPrice: String,
        discount: String,
    ): Long {
        var billId = 0L
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val zBon = TestData.createZBonFullDay(shop, admin, nummer = 1L)
            val tws = TestData.createTableWithSession("DGP$billNumber")
            val session = TableSession.findById(tws.session.id)!!
            val now = currentTimeStamp()
            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = billNumber
                    this.billAmount = BigDecimal(finalPrice)
                    this.paidAmount = BigDecimal(finalPrice)
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            billId = bill.id.value
            val cat = TestData.createCategory("Guard Cat").id.value
            OrderItem.new {
                this.session = session
                this.product = TestData.createProduct(cat, "Guard Item", "GRD")
                this.unitPrice = BigDecimal(unitPrice)
                this.finalPrice = BigDecimal(finalPrice)
                this.discount = BigDecimal(discount)
                this.vat = TestData.createDefaultVatRates()[0]
                this.itemStatus = ItemStatus.PAID
                this.takeAway = false
                this.bill = bill
            }
        }
        return billId
    }

    private fun export(): ByteArray =
        runBlocking {
            service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
        }

    @Test
    fun `sub-cent discount that rounds away does not abort the export`() {
        // charged 0.2510 * 0.99 -> OrderServiceImpl stores round2 = 0.25 (persisted 0.2500).
        // Writer: round2(0.2510) - round2(0.2500) = 0.00 -> no Rabatt row, no recordDiscount.
        // Reader (pre-fix): 0.2510 - 0.2500 = 0.0010 != 0 -> plan.discountPos -> throw.
        val billId = seedBillWithItem(billNumber = "9101", unitPrice = "0.2510", finalPrice = "0.25", discount = "0.99")
        val zip = export()
        val bonId = "B-$billId"

        val lines = readRows(zip, "lines.csv").filter { it["BON_ID"] == bonId }
        val linesVat = readRows(zip, "lines_vat.csv").filter { it["BON_ID"] == bonId }

        assertEquals(0, lines.count { it["GV_TYP"] == "Rabatt" }, "lines.csv must emit no Rabatt row, got $lines")
        assertEquals(lines.size, linesVat.size, "lines_vat must have exactly one row per lines.csv row")
        assertEquals(
            0,
            readRows(zip, "businesscases.csv").count { it["GV_TYP"] == "Rabatt" },
            "businesscases must not carry a Rabatt aggregate either",
        )

        // Three-way gross agreement on THIS fixture: Σ lines.STK_BR == UMS_BRUTTO == the single
        // lines_vat gross. UMS_BRUTTO derives from bill.billAmount (potentially scale-4) while
        // lines.csv sums per-line 2dp values — suppressing the zero-rounding Rabatt row must not
        // break that identity.
        val kopf = readRows(zip, "transactions.csv").single { it["BON_ID"] == bonId }
        val lineSum = lines.sumOf { BigDecimal(it["STK_BR"]!!.replace(',', '.')) }
        assertEquals(BigDecimal("0.25"), lineSum, "Σ lines.csv STK_BR")
        assertEquals("0,25", kopf["UMS_BRUTTO"], "UMS_BRUTTO")
        assertEquals(
            BigDecimal("0.25"),
            linesVat.sumOf { BigDecimal(it["POS_BRUTTO"]!!.replace(',', '.')) },
            "Σ lines_vat POS_BRUTTO",
        )
    }

    @Test
    fun `a real discount still emits exactly one Rabatt row in all three producers`() {
        val billId = seedBillWithItem(billNumber = "9102", unitPrice = "10.00", finalPrice = "9.00", discount = "0.90")
        val zip = export()
        val bonId = "B-$billId"

        val lines = readRows(zip, "lines.csv").filter { it["BON_ID"] == bonId }
        assertEquals(1, lines.count { it["GV_TYP"] == "Rabatt" }, "lines.csv Rabatt row, got $lines")

        val linesVat = readRows(zip, "lines_vat.csv").filter { it["BON_ID"] == bonId }
        assertEquals(lines.size, linesVat.size, "lines_vat must have exactly one row per lines.csv row")

        val bc = readRows(zip, "businesscases.csv").filter { it["GV_TYP"] == "Rabatt" }
        assertTrue(bc.isNotEmpty(), "businesscases must carry the Rabatt aggregate")
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
