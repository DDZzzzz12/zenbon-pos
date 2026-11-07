package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.features.zBon.domain.models.ZBonPaymentSummary
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
 * Regression test for the cash-reconciliation defect (audit finding H2): `cashpointclosing.csv`'s
 * `Z_SE_BARZAHLUNGEN` was sourced from the Kassenbuch till header (`zBon.kasseBuch.firstOrNull()
 * ?.totalAmount`) — a cash-in-drawer balance that already nets out cash tips / Kassenbuch cash
 * movements — instead of the DSFinV-K definition: "in die Basiswährung umgerechnete Summe der
 * Zahlart 'Bar'". Meanwhile `payment.csv` sums Bar tender independently from
 * [ZBonPaymentSummary]. Once a cash tip exists, the till balance and the raw Bar tender diverge
 * and the two files stop reconciling.
 *
 * The fixture below pins a cash sale + a cash tip (so the till header is deliberately built to
 * differ from the raw cash tender) plus a card sale, and asserts `payment.csv` and
 * `cashpointclosing.csv` always reconcile against the SAME [ZBonPaymentSummary] source.
 */
@DisplayName("DSFinV-K export — cash reconciliation")
class DsfinvkCashReconciliationTest {
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
    fun `Z_SE_BARZAHLUNGEN reconciles to the Bar rows of payment csv, not the till balance`() {
        transaction {
            val admin = TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            val cash = TestData.createPaymentType("Cash", isCash = true)
            val card = TestData.createPaymentType("Card", isCash = false)
            val tws = TestData.createTableWithSession("CASHREC1")
            val now = currentTimeStamp()

            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-CASHREC-TEST"
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

            // Cash sale WITH a cash tip: paidAmount (cash tendered) = billAmount + tip.
            val cashSale =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "CASHREC-A"
                    this.billAmount = BigDecimal("18.50")
                    this.paidAmount = BigDecimal("20.50")
                    this.tips = BigDecimal("2.00")
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            Payment.new {
                this.bill = cashSale
                this.paymentType = cash
                this.amount = BigDecimal("20.50")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }

            // Card sale (non-cash) — must NOT bleed into Z_SE_BARZAHLUNGEN but must count
            // towards Z_SE_ZAHLUNGEN.
            val cardSale =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "CASHREC-B"
                    this.billAmount = BigDecimal("12.00")
                    this.paidAmount = BigDecimal("12.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            Payment.new {
                this.bill = cardSale
                this.paymentType = card
                this.amount = BigDecimal("12.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }

            // payment.csv (Z_Zahlart) derives from the per-closing payment summaries, not the
            // raw Payment rows — the cash summary is the FULL cash tender including the tip.
            ZBonPaymentSummary.new {
                this.zBon = zBon
                this.paymentType = cash
                this.currency = "EUR"
                this.amount = BigDecimal("20.50")
            }
            ZBonPaymentSummary.new {
                this.zBon = zBon
                this.paymentType = card
                this.currency = "EUR"
                this.amount = BigDecimal("12.00")
            }

            // Kassenbuch till header: deliberately NOT equal to the raw cash tender (20.50) —
            // this models "cash in drawer after tips" (e.g. tips routed out of the drawer to
            // staff), which is what the pre-fix code wrongly used as Z_SE_BARZAHLUNGEN.
            // It IS the Z-Bon aggregate entry (as its belegtext says), so it carries the flag
            // production sets — since the H2 fix, non-aggregate cash movements are Beleg+Bar
            // and legitimately roll into payment.csv; the aggregate stays excluded.
            KassenbuchHeader.new {
                this.shop = shop
                this.cashBook = CashBookRepositoryImpl().fiscalBookFor(shop)
                this.employee = admin
                this.isZbonAggregate = true
                this.entryType = KassenbuchEntryType.CASH_SALE
                this.belegNr = "2026-07-0001"
                this.belegDatum = "11.07.2026"
                this.belegtext = "Z-Bon Kassenbuch aggregate"
                this.waehrung = "EUR"
                this.bu = null
                this.gegenkonto = null
                this.kost1 = null
                this.kost2 = null
                this.nachricht = null
                this.totalAmount = BigDecimal("18.50")
                this.currentBalance = BigDecimal("18.50")
                this.zBon = zBon
                this.createdAt = now
            }
        }

        val zip =
            runBlocking {
                service.generateExport(
                    DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"),
                )
            }

        val paymentCsv = readZipEntry(zip, "payment.csv")
        assertNotNull(paymentCsv, "payment.csv must be present in the export ZIP")
        val closingCsv = readZipEntry(zip, "cashpointclosing.csv")
        assertNotNull(closingCsv, "cashpointclosing.csv must be present in the export ZIP")

        val paymentRows = parseCsv(paymentCsv)
        val closingRows = parseCsv(closingCsv)
        assertEquals(1, closingRows.size, "fixture builds exactly one Z-Bon closing row")

        val barSum =
            paymentRows
                .filter { it["ZAHLART_TYP"] == "Bar" }
                .sumOf { parseAmount(it["Z_ZAHLART_BETRAG"]!!) }
        val allSum = paymentRows.sumOf { parseAmount(it["Z_ZAHLART_BETRAG"]!!) }

        val zSeBarzahlungen = parseAmount(closingRows.first()["Z_SE_BARZAHLUNGEN"]!!)
        val zSeZahlungen = parseAmount(closingRows.first()["Z_SE_ZAHLUNGEN"]!!)

        assertTrue(
            barSum.compareTo(BigDecimal("20.50")) == 0,
            "sanity: payment.csv Bar rows must sum to the raw cash tender (20.50); got $barSum",
        )
        assertTrue(
            barSum.compareTo(zSeBarzahlungen) == 0,
            "Z_SE_BARZAHLUNGEN ($zSeBarzahlungen) must equal the summed Bar rows of payment.csv " +
                "($barSum) — both derive from the same ZBonPaymentSummary cash rows",
        )
        assertTrue(
            allSum.compareTo(zSeZahlungen) == 0,
            "Z_SE_ZAHLUNGEN ($zSeZahlungen) must equal the sum of ALL payment.csv rows ($allSum)",
        )
    }

    /**
     * Reads a single CSV entry (by exact filename) out of the export ZIP as text, or null.
     *
     * Matches on the exact basename (not a bare `endsWith` on the full entry name) — several
     * DSFinV-K filenames share a suffix (e.g. "datapayment.csv" ends with "payment.csv"), so a
     * naive suffix match can silently return the wrong file's content.
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
