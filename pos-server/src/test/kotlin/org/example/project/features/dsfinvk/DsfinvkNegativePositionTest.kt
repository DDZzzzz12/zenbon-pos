package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.order.domain.models.Cancellation
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

/**
 * Tz. 4.2.5 (audit BPOS-5/H13a): on reversals „Lediglich das Vorzeichen für das Feld MENGE
 * ändert sich" — MENGE carries the sign, STK_BR stays the (Grund)Preis. String-prefix
 * negation of an already-negative deposit-return amount produced the unparseable `--0,25`.
 */
@DisplayName("DSFinV-K negative positions: MENGE carries the sign (BPOS-5, H13a)")
class DsfinvkNegativePositionTest {
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
    fun `storno line negates MENGE and keeps STK_BR positive`() {
        var reverseBonId = ""
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val zBon = TestData.createZBonFullDay(shop, admin, nummer = 1L)
            val tws = TestData.createTableWithSession("NEG1")
            val session = TableSession.findById(tws.session.id)!!
            val now = currentTimeStamp()
            val original =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "8001"
                    this.billAmount = BigDecimal("10.00")
                    this.paidAmount = BigDecimal("10.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            OrderItem.new {
                this.session = session
                this.product = TestData.createProduct(TestData.createCategory("Neg Cat").id.value, "Cola", "COLA")
                this.unitPrice = BigDecimal("10.00")
                this.finalPrice = BigDecimal("10.00")
                this.vat = TestData.createDefaultVatRates()[0]
                this.itemStatus = ItemStatus.PAID
                this.takeAway = false
                this.bill = original
            }
            val reverse =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "8002"
                    this.billAmount = BigDecimal("-10.00")
                    this.paidAmount = BigDecimal("-10.00")
                    this.tips = BigDecimal.ZERO
                    this.deletedBill = original
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            reverseBonId = "B-${reverse.id.value}"
        }

        val lines = readRows(export(), "lines.csv").filter { it["BON_ID"] == reverseBonId }
        val line = lines.single()
        assertEquals("-1,000", line["MENGE"], "the reversal flips MENGE, not the price")
        assertEquals("10,00", line["STK_BR"], "STK_BR keeps the (Grund)Preis")
    }

    @Test
    fun `cancelled deposit-return line renders parseable values with MENGE sign`() {
        var cancellationBonId = ""
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val zBon = TestData.createZBonFullDay(shop, admin, nummer = 1L)
            val tws = TestData.createTableWithSession("NEG2")
            val session = TableSession.findById(tws.session.id)!!
            val now = currentTimeStamp()
            // Deposit-return line: negative value by data contract (ZBonPfandAggregator).
            val item =
                OrderItem.new {
                    this.session = session
                    this.product = TestData.createProduct(TestData.createCategory("Pfand Cat").id.value, "Flasche Rückgabe", "PFR")
                    this.unitPrice = BigDecimal("-0.25")
                    this.finalPrice = BigDecimal("-0.25")
                    this.vat = TestData.createDefaultVatRates()[0]
                    this.itemStatus = ItemStatus.CANCELLED
                    this.takeAway = false
                }
            val cancellation =
                Cancellation.new {
                    this.orderItem = item
                    this.reason = "falsch erfasst"
                    this.employeeId = admin
                    this.createdAt = now
                    this.zBon = zBon
                    this.tseSigningStatus = TseSigningStatus.PENDING
                    this.isTraining = false
                }
            cancellationBonId = "C-${cancellation.id.value}"
        }

        val zip = export()
        val kopf = readRows(zip, "transactions.csv").single { it["BON_ID"] == cancellationBonId }
        assertEquals("0,25", kopf["UMS_BRUTTO"], "reversal of a −0,25 line is +0,25 — never --0,25")

        val line = readRows(zip, "lines.csv").single { it["BON_ID"] == cancellationBonId }
        assertEquals("1,000", line["MENGE"], "reversing a negative-value line yields a positive line via MENGE")
        assertEquals("0,25", line["STK_BR"])
    }

    /**
     * INV-F7: legacy scale-4 finalPrice (e.g. 4.9950, written before TSER-5 normalized
     * finalPrice to 2dp at creation) must still yield a self-consistent Bonpos: the item row
     * minus the Rabatt row must equal round2(finalPrice) — the exact value the transactions
     * header (UMS_BRUTTO) sums. Rounding the discount difference once (round2(charged −
     * finalPrice) = round2(9.99 − 4.9950) = 5.00) drifted Σ lines to 4.99 ≠ header 5.00; rounding
     * both components first (round2(9.99) − round2(4.9950) = 9.99 − 5.00 = 4.99) restores Σ = 5.00.
     */
    @Test
    fun `legacy scale-4 finalPrice keeps discount Bonpos self-consistent with the header`() {
        var bonId = ""
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val zBon = TestData.createZBonFullDay(shop, admin, nummer = 1L)
            val tws = TestData.createTableWithSession("DISC1")
            val session = TableSession.findById(tws.session.id)!!
            val now = currentTimeStamp()
            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "9101"
                    // UMS_BRUTTO anchor = round2(finalPrice) = 5.00.
                    this.billAmount = BigDecimal("5.00")
                    this.paidAmount = BigDecimal("5.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            // LEGACY scale-4 finalPrice: 9.99 * 0.5 = 4.9950 (the pre-TSER-5 stored value).
            OrderItem.new {
                this.session = session
                this.product = TestData.createProduct(TestData.createCategory("Disc Cat").id.value, "Latte", "LAT")
                this.unitPrice = BigDecimal("9.99")
                this.finalPrice = BigDecimal("4.9950")
                this.discount = BigDecimal("0.50")
                this.vat = TestData.createDefaultVatRates()[0]
                this.itemStatus = ItemStatus.PAID
                this.takeAway = false
                this.bill = bill
            }
            bonId = "B-${bill.id.value}"
        }

        val zip = export()
        val header = readRows(zip, "transactions.csv").single { it["BON_ID"] == bonId }
        assertEquals("5,00", header["UMS_BRUTTO"], "header sums round2(finalPrice) = 5,00")

        val lines = readRows(zip, "lines.csv").filter { it["BON_ID"] == bonId }
        val itemRow = lines.single { it["ARTIKELTEXT"] == "Latte" }
        val rabattRow = lines.single { it["GV_TYP"] == DsfinvkExportDto.BusinessCaseTypes.RABATT }
        assertEquals("9,99", itemRow["STK_BR"], "item row carries the pre-discount Grundpreis")
        assertEquals("-4,99", rabattRow["STK_BR"], "round2(9,99) − round2(4,9950) = 9,99 − 5,00 = 4,99")

        // Σ(item + Rabatt) MENGE×STK_BR == UMS_BRUTTO 5,00 (both MENGE = 1).
        val sum = germanToBigDecimal(itemRow["STK_BR"]!!) + germanToBigDecimal(rabattRow["STK_BR"]!!)
        assertEquals(0, BigDecimal("5.00").compareTo(sum), "item − Rabatt must equal the header's 5,00")
    }

    private fun germanToBigDecimal(value: String): BigDecimal = BigDecimal(value.replace(".", "").replace(",", "."))

    private fun export(): ByteArray =
        runBlocking {
            service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
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
