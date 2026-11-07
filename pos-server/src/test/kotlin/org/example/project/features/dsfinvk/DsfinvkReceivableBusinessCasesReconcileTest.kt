package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.models.BillVatDetail
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.payment.domain.models.PaymentType
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
import kotlin.test.assertTrue

/**
 * T9-5 (v1.1.0 Task 9): `businesscases.csv` must reconcile with the Einzelaufzeichnung per
 * (Z_NR, GV_TYP, UST_SCHLUESSEL) — Σ lines_vat, joined to lines.csv on (BON_ID, POS_ZEILE) for the
 * GV_TYP, equals the closing's business-case aggregate. `generateBusinessCases` had no branch for
 * the two receivable business cases, so a closing with a bill settled "auf Rechnung" and a later
 * clearing carried `Forderungsentstehung` / `Forderungsaufloesung` lines but no matching
 * business-case rows, and a closing holding only a clearing had no business-case rows at all.
 * lines_vat also lacked the Forderungsentstehung twin, so Σ lines_vat fell short of UMS_BRUTTO.
 */
@DisplayName("DSFinV-K businesscases reconcile with lines_vat for receivables (T9-5)")
class DsfinvkReceivableBusinessCasesReconcileTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    /** A 23.80 € sale at 19 % settled "auf Rechnung", and — when [withClearing] — its cash clearing. */
    private fun seed(
        withSale: Boolean,
        withClearing: Boolean,
    ) {
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val zBon = TestData.createZBonFullDay(shop, admin, nummer = 1L)
            val vat19 = TestData.createDefaultVatRates()[0]
            val cashType = TestData.createPaymentType("Bar", isCash = true)
            val onAccount =
                TestData.createPaymentType("Rechnung", isCash = false).also {
                    PaymentType.findById(it.id)!!.createsReceivable = true
                }
            val category = TestData.createCategory("T95 Cat")
            val product = TestData.createProduct(category.id.value, "T95 Product", "T95001")
            val session = TableSession.findById(TestData.createTableWithSession("T95").session.id)!!
            val now = currentTimeStamp()

            val sale =
                if (!withSale) {
                    // The sale happened in an earlier closing: this one holds only the clearing.
                    null
                } else {
                    Bill
                        .new {
                            this.employeeId = admin
                            this.tableSession = session
                            this.billNumber = "T95-1"
                            this.billAmount = BigDecimal("23.80")
                            this.paidAmount = BigDecimal("23.80")
                            this.tips = BigDecimal.ZERO
                            this.startTime = now
                            this.endTime = now
                            this.zBon = zBon
                        }.also { bill ->
                            OrderItem.new {
                                this.session = session
                                this.product = product
                                this.bill = bill
                                this.unitPrice = BigDecimal("23.80")
                                this.finalPrice = BigDecimal("23.80")
                                this.itemStatus = ItemStatus.PAID
                                this.vat = vat19
                                this.takeAway = false
                            }
                            BillVatDetail.new {
                                this.bill = bill
                                this.vat = vat19
                                this.totalAmount = BigDecimal("23.80")
                                this.netAmount = BigDecimal("20.00")
                                this.vatAmount = BigDecimal("3.80")
                            }
                            Payment.new {
                                this.bill = bill
                                this.paymentType = onAccount
                                this.amount = BigDecimal("23.80")
                                this.currency = "EUR"
                                this.paymentStatus = PaymentStatus.COMPLETED
                                this.paymentTime = now
                                this.createsReceivable = true
                            }
                        }
                }

            if (withClearing) {
                val invoice =
                    SalesInvoice.new {
                        this.shop = shop
                        this.revenueOrigin = RevenueOrigin.POS_RECEIVABLE
                        this.status = SalesInvoiceStatus.ISSUED
                        this.buyerName = "Catering Nord GmbH"
                        this.issueDate = now
                        this.serviceDate = now
                        this.billId = sale?.id
                    }
                val clearing =
                    Bill.new {
                        this.employeeId = admin
                        this.tableSession = session
                        this.billNumber = "T95-2"
                        this.billAmount = BigDecimal("23.80")
                        this.paidAmount = BigDecimal("23.80")
                        this.tips = BigDecimal.ZERO
                        this.startTime = now
                        this.endTime = now
                        this.zBon = zBon
                        this.clearsSalesInvoiceId = invoice.id.value
                    }
                Payment.new {
                    this.bill = clearing
                    this.paymentType = cashType
                    this.amount = BigDecimal("23.80")
                    this.currency = "EUR"
                    this.paymentStatus = PaymentStatus.COMPLETED
                    this.paymentTime = now
                    this.createsReceivable = false
                }
            }
        }
    }

    @Test
    fun `businesscases equals the sum of lines_vat per closing, business case and VAT key`() {
        seed(withSale = true, withClearing = true)
        val zip = export()

        val businessCases = readRows(zip, "businesscases.csv")
        assertBusinessCasesReconcileWithLinesVat(zip)

        val entstehung = businessCases.single { it["GV_TYP"] == "Forderungsentstehung" }
        assertEquals("5", entstehung["UST_SCHLUESSEL"])
        assertEquals("-23,80", entstehung["Z_UMS_BRUTTO"])
        assertEquals("0,00", entstehung["Z_UST"])
        val aufloesung = businessCases.single { it["GV_TYP"] == "Forderungsaufloesung" }
        assertEquals("5", aufloesung["UST_SCHLUESSEL"])
        assertEquals("23,80", aufloesung["Z_UMS_BRUTTO"])
        assertEquals("0,00", aufloesung["Z_UST"])
        // Soll-Versteuerung: the VAT stays on the sale's real key.
        val umsatz = businessCases.single { it["GV_TYP"] == "Umsatz" }
        assertEquals("1", umsatz["UST_SCHLUESSEL"])
        assertEquals("3,80", umsatz["Z_UST"])

        assertGrossReconcilesPerBon(zip)
    }

    @Test
    fun `a closing holding only a clearing still carries its business case`() {
        seed(withSale = false, withClearing = true)
        val zip = export()

        val businessCases = readRows(zip, "businesscases.csv")
        assertTrue(businessCases.isNotEmpty(), "a clearing-only closing must not have an empty businesscases.csv")
        assertBusinessCasesReconcileWithLinesVat(zip)
        assertGrossReconcilesPerBon(zip)
    }

    /** Σ lines_vat (GV_TYP from lines.csv) == businesscases, per (Z_NR, GV_TYP, UST_SCHLUESSEL). */
    private fun assertBusinessCasesReconcileWithLinesVat(zip: ByteArray) {
        val gvTypByLine =
            readRows(zip, "lines.csv").associate { (it.getValue("BON_ID") to it.getValue("POS_ZEILE")) to it.getValue("GV_TYP") }
        val fromLinesVat =
            readRows(zip, "lines_vat.csv")
                .groupBy {
                    val gvTyp =
                        gvTypByLine[it.getValue("BON_ID") to it.getValue("POS_ZEILE")]
                            ?: error("lines_vat row without a lines.csv parent: $it")
                    Triple(it.getValue("Z_NR"), gvTyp, it.getValue("UST_SCHLUESSEL"))
                }.mapValues { (_, rows) -> sums(rows, "POS_BRUTTO", "POS_NETTO", "POS_UST") }
        val fromBusinessCases =
            readRows(zip, "businesscases.csv")
                .groupBy { Triple(it.getValue("Z_NR"), it.getValue("GV_TYP"), it.getValue("UST_SCHLUESSEL")) }
                .mapValues { (_, rows) -> sums(rows, "Z_UMS_BRUTTO", "Z_UMS_NETTO", "Z_UST") }

        (fromLinesVat.keys + fromBusinessCases.keys).forEach { key ->
            val zero = listOf(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
            val lv = fromLinesVat[key] ?: zero
            val bc = fromBusinessCases[key] ?: zero
            lv.zip(bc).forEachIndexed { i, (l, b) ->
                assertEquals(
                    0,
                    l.compareTo(b),
                    "(Z_NR, GV_TYP, UST_SCHLUESSEL)=$key column $i: Σ lines_vat=$lv but businesscases=$bc",
                )
            }
        }
    }

    /** Per BON_ID: UMS_BRUTTO == Σ lines_vat.POS_BRUTTO == Σ transactions_vat.BON_BRUTTO. */
    private fun assertGrossReconcilesPerBon(zip: ByteArray) {
        val lv = readRows(zip, "lines_vat.csv").groupBy { it.getValue("BON_ID") }
        val tv = readRows(zip, "transactions_vat.csv").groupBy { it.getValue("BON_ID") }
        readRows(zip, "transactions.csv").forEach { kopf ->
            val bon = kopf.getValue("BON_ID")
            val ums = amount(kopf.getValue("UMS_BRUTTO"))
            val lvSum = lv[bon].orEmpty().sumOf { amount(it.getValue("POS_BRUTTO")) }
            val tvSum = tv[bon].orEmpty().sumOf { amount(it.getValue("BON_BRUTTO")) }
            assertEquals(0, lvSum.compareTo(ums), "$bon: Σ lines_vat ($lvSum) must equal UMS_BRUTTO ($ums)")
            assertEquals(0, tvSum.compareTo(ums), "$bon: Σ transactions_vat ($tvSum) must equal UMS_BRUTTO ($ums)")
        }
    }

    private fun sums(
        rows: List<Map<String, String>>,
        vararg columns: String,
    ): List<BigDecimal> = columns.map { col -> rows.sumOf { amount(it.getValue(col)) } }

    private fun amount(value: String): BigDecimal = BigDecimal(value.replace(",", "."))

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
