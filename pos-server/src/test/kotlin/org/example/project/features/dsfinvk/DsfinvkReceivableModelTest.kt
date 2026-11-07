package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.payment.domain.models.PaymentType
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
 * Slice 4c: a bill settled "auf Rechnung" is a **Forderungsentstehung** — the goods were sold but no
 * money moved, so the header reports only what was tendered, the deferred gross is decomposed to
 * key 5, and the receivable leg is not a tender.
 *
 * Soll-Versteuerung (spec §5.4): the real VAT keys keep their amounts, because under Soll the VAT is
 * owed at the sale. The shape mirrors [DsfinvkVoucherRedemptionModelTest] deliberately — a voucher
 * share and a receivable share are both "not a tender", and the export treats them the same way.
 */
@DisplayName("DSFinV-K Forderungsentstehung for an auf-Rechnung bill (4c)")
class DsfinvkReceivableModelTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    /** One bill of [gross], settled with an optional cash leg and an optional receivable leg. */
    private fun seedBill(
        gross: String,
        receivable: String?,
        cash: String?,
    ): Long {
        var billId = 0L
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val zBon = TestData.createZBonFullDay(shop, admin, nummer = 1L)
            val cashType = TestData.createPaymentType("Bar", isCash = true)
            val transferType =
                TestData.createPaymentType("Ueberweisung", isCash = false).also {
                    PaymentType.findById(it.id)!!.createsReceivable = true
                }
            val tws = TestData.createTableWithSession("RCV1")
            val session = TableSession.findById(tws.session.id)!!
            val now = currentTimeStamp()
            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "4712"
                    this.billAmount = BigDecimal(gross)
                    this.paidAmount = BigDecimal(gross)
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            billId = bill.id.value
            receivable?.let {
                Payment.new {
                    this.bill = bill
                    this.paymentType = transferType
                    this.amount = BigDecimal(it)
                    this.currency = "EUR"
                    this.paymentStatus = PaymentStatus.COMPLETED
                    this.paymentTime = now
                    this.createsReceivable = true
                }
            }
            cash?.let {
                Payment.new {
                    this.bill = bill
                    this.paymentType = cashType
                    this.amount = BigDecimal(it)
                    this.currency = "EUR"
                    this.paymentStatus = PaymentStatus.COMPLETED
                    this.paymentTime = now
                    this.createsReceivable = false
                }
            }
        }
        return billId
    }

    private fun export(): ByteArray =
        runBlocking {
            service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
        }

    @Test
    fun `a fully on-account bill reports no turnover, a negative key-5 row and a Keine 0 tender`() {
        val billId = seedBill(gross = "23.00", receivable = "23.00", cash = null)
        val zip = export()

        val kopf = readRows(zip, "transactions.csv").single { it["BON_ID"] == "B-$billId" }
        assertEquals("0,00", kopf["UMS_BRUTTO"], "no money moved at the till")

        val vat = readRows(zip, "transactions_vat.csv").filter { it["BON_ID"] == "B-$billId" }
        assertTrue(
            vat.any { it["UST_SCHLUESSEL"] == "5" && it["BON_BRUTTO"] == "-23,00" },
            "the deferred gross is decomposed to key 5, got $vat",
        )

        val pay = readRows(zip, "datapayment.csv").filter { it["BON_ID"] == "B-$billId" }
        assertEquals(1, pay.size, "a receivable is not a tender, so only the synthetic row remains: $pay")
        assertEquals("Keine", pay.single()["ZAHLART_TYP"])
        assertEquals("0,00", pay.single()["BASISWAEH_BETRAG"])

        val lines = readRows(zip, "lines.csv").filter { it["BON_ID"] == "B-$billId" }
        assertTrue(
            lines.any { it["GV_TYP"] == "Forderungsentstehung" && it["STK_BR"] == "-23,00" },
            "lines.csv carries the balancing Forderungsentstehung row, got ${lines.map { it["GV_TYP"] to it["STK_BR"] }}",
        )
    }

    @Test
    fun `the worked mixed tender reports the cash share and defers only the rest`() {
        val billId = seedBill(gross = "80.00", receivable = "50.00", cash = "30.00")
        val zip = export()

        val kopf = readRows(zip, "transactions.csv").single { it["BON_ID"] == "B-$billId" }
        assertEquals("30,00", kopf["UMS_BRUTTO"], "the cash share moved and must be reported, not 0 and not 80")

        val vat = readRows(zip, "transactions_vat.csv").filter { it["BON_ID"] == "B-$billId" }
        assertTrue(
            vat.any { it["UST_SCHLUESSEL"] == "5" && it["BON_BRUTTO"] == "-50,00" },
            "only the receivable share is deferred, got $vat",
        )

        val pay = readRows(zip, "datapayment.csv").filter { it["BON_ID"] == "B-$billId" }
        assertEquals(listOf("Bar"), pay.map { it["ZAHLART_TYP"] }, "only the real tender is listed")
        assertEquals("30,00", pay.single()["BASISWAEH_BETRAG"])
    }

    @Test
    fun `an ordinary cash bill is untouched by the receivable branches`() {
        val billId = seedBill(gross = "23.00", receivable = null, cash = "23.00")
        val zip = export()

        val kopf = readRows(zip, "transactions.csv").single { it["BON_ID"] == "B-$billId" }
        assertEquals("23,00", kopf["UMS_BRUTTO"])

        val vat = readRows(zip, "transactions_vat.csv").filter { it["BON_ID"] == "B-$billId" }
        assertTrue(vat.none { it["UST_SCHLUESSEL"] == "5" }, "no key-5 decomposition without a receivable: $vat")

        val lines = readRows(zip, "lines.csv").filter { it["BON_ID"] == "B-$billId" }
        assertTrue(lines.none { it["GV_TYP"] == "Forderungsentstehung" }, "and no balancing line")
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
