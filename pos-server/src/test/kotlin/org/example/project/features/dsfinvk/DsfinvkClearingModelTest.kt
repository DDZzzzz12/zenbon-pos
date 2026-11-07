package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
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
 * Slice 4c-2c: a receivable cleared at the counter is a **Forderungsauflösung**. The clearing Bill
 * carries the tendered money (`UMS_BRUTTO` == the amount) but no goods — it has no `bill_vat_detail`
 * and no order items — so all four VAT/line/reference rows are synthesised at key 5 with zero USt.
 * Under Soll the VAT was already booked at the Forderungsentstehung (worked example 1.6).
 */
@DisplayName("DSFinV-K Forderungsauflösung for a till clearing (4c-2c)")
class DsfinvkClearingModelTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    /** Result of seeding: the clearing bill and the original bill its invoice references. */
    private data class Seeded(
        val clearingBillId: Long,
        val originalBillId: Long,
    )

    /** A 23.00 receivable, issued against an original bill, cleared by a single [cashTender] leg. */
    private fun seedClearing(cashTender: Boolean): Seeded {
        var clearingId = 0L
        var originalId = 0L
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val zBon = TestData.createZBonFullDay(shop, admin, nummer = 1L)
            val tender =
                if (cashTender) {
                    TestData.createPaymentType("Bar", isCash = true)
                } else {
                    TestData.createPaymentType("Karte", isCash = false)
                }
            val tws = TestData.createTableWithSession("CLR1")
            val session = TableSession.findById(tws.session.id)!!
            val now = currentTimeStamp()

            val originalBill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "5000"
                    this.billAmount = BigDecimal("23.00")
                    this.paidAmount = BigDecimal("23.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            originalId = originalBill.id.value

            val invoice =
                SalesInvoice.new {
                    this.shop = shop
                    this.revenueOrigin = RevenueOrigin.POS_RECEIVABLE
                    this.status = SalesInvoiceStatus.ISSUED
                    this.buyerName = "Catering Nord GmbH"
                    this.issueDate = now
                    this.serviceDate = now
                    this.billId = originalBill.id
                }

            val clearingBill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "5001"
                    this.billAmount = BigDecimal("23.00")
                    this.paidAmount = BigDecimal("23.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                    this.clearsSalesInvoiceId = invoice.id.value
                }
            clearingId = clearingBill.id.value

            Payment.new {
                this.bill = clearingBill
                this.paymentType = tender
                this.amount = BigDecimal("23.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
                this.createsReceivable = false
            }
        }
        return Seeded(clearingId, originalId)
    }

    private fun export(): ByteArray =
        runBlocking {
            service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
        }

    @Test
    fun `a cash clearing synthesises the four key-5 Forderungsaufloesung rows`() {
        val (clearingId, originalId) = seedClearing(cashTender = true)
        val zip = export()
        val bon = "B-$clearingId"

        val kopf = readRows(zip, "transactions.csv").single { it["BON_ID"] == bon }
        assertEquals("23,00", kopf["UMS_BRUTTO"], "the tendered money moved and is reported")

        val vat = readRows(zip, "transactions_vat.csv").filter { it["BON_ID"] == bon }
        assertEquals(1, vat.size, "exactly one transactions_vat row, got $vat")
        assertEquals("5", vat.single()["UST_SCHLUESSEL"])
        assertEquals("23,00", vat.single()["BON_BRUTTO"])
        assertEquals("23,00", vat.single()["BON_NETTO"])
        assertEquals("0,00", vat.single()["BON_UST"])

        val lines = readRows(zip, "lines.csv").filter { it["BON_ID"] == bon }
        assertEquals(1, lines.size, "exactly one lines row, got ${lines.map { it["GV_TYP"] }}")
        assertEquals("Forderungsaufloesung", lines.single()["GV_TYP"])
        assertEquals("23,00", lines.single()["STK_BR"])
        assertEquals(emptyList(), lines.filter { it["GV_TYP"] == "Umsatz" }, "a clearing sells nothing")

        val linesVat = readRows(zip, "lines_vat.csv").filter { it["BON_ID"] == bon }
        assertEquals(1, linesVat.size, "exactly one lines_vat row, got $linesVat")
        assertEquals("5", linesVat.single()["UST_SCHLUESSEL"])
        assertEquals("23,00", linesVat.single()["POS_BRUTTO"])
        assertEquals("0,00", linesVat.single()["POS_UST"])

        val refs = readRows(zip, "references.csv").filter { it["BON_ID"] == bon }
        assertEquals(1, refs.size, "one reference to the original Vorgang, got $refs")
        assertEquals("Transaktion", refs.single()["REF_TYP"])
        assertEquals("B-$originalId", refs.single()["REF_BON_ID"], "points at the bill that opened the receivable")

        val pay = readRows(zip, "datapayment.csv").filter { it["BON_ID"] == bon }
        assertEquals("Bar", pay.single()["ZAHLART_TYP"], "a cash clearing is a cash tender")
    }

    @Test
    fun `a card clearing books its tender as a non-cash ECKarte`() {
        val (clearingId, _) = seedClearing(cashTender = false)
        val zip = export()
        val bon = "B-$clearingId"

        val pay = readRows(zip, "datapayment.csv").filter { it["BON_ID"] == bon }
        assertEquals("ECKarte", pay.single()["ZAHLART_TYP"], "a card ('Karte') clearing books a non-cash tender")

        // The synthesised revenue rows are tender-independent.
        val vat = readRows(zip, "transactions_vat.csv").filter { it["BON_ID"] == bon }
        assertEquals("5", vat.single()["UST_SCHLUESSEL"])
        assertEquals("0,00", vat.single()["BON_UST"])
    }

    @Test
    fun `validate does not count a clearing bill as missing its VAT breakdown`() {
        // Both seeded bills carry no bill_vat_detail: the clearing by design, the original sale
        // because the fixture books none. Only the SALE may be reported — pre-fix the warning read
        // "2 bills are missing VAT breakdown details" (E2E 2026-09-20: "3 bills" on a package that
        // reconciles, all three Forderungsausgleich clearings).
        seedClearing(cashTender = true)

        val result = runBlocking { service.validateExportData("2000-01-01", "2100-01-01") }

        val vatWarnings = result.warnings.filter { it.contains("missing VAT breakdown") }
        assertEquals(listOf("1 bills are missing VAT breakdown details"), vatWarnings)
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
