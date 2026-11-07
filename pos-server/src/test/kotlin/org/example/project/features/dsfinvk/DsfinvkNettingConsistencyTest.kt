package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.table.domain.models.TableSession
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testJson
import org.example.project.testutils.testKassenbuchDocuments
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
 * Round-3 INV-F6: two aggregators rounded per-component (per line/item) then summed, drifting a
 * cent from the single-netted target whenever ≥2 rows share a VAT rate.
 *
 *  (a) [DsfinvkExportServiceImpl.generateBonkopfUst]'s Kassenbuch branch netted each line at scale
 *      4 then re-rounded to 2 (double rounding), so BON_NETTO could differ by 0,01 from the
 *      Σ lines_vat POS_NETTO the KB lines_vat branch nets directly at scale 2.
 *  (b) businesscases (`generateBusinessCases`) netted EACH item independently at scale 2 and
 *      summed, so Z_UMS_NETTO drifted a cent from the single-netted BillVatDetail (transactions_vat)
 *      whenever ≥2 items shared a rate.
 *
 * Fix: net the group's gross ONCE (KB Bonkopf_USt nets each line at scale 2 to match its lines_vat;
 * businesscases Umsatz-family rows net once per BILL via `BillVatNetting`), mirroring
 * `applyLineVatGroupResidual`. F43 moved the businesscases netting from the closing to the bill.
 * The several-bills-per-rate case is pinned in `DsfinvkComplianceGateTest`.
 */
@DisplayName("DSFinV-K netting consistency — KB Bonkopf_USt vs lines_vat, businesscases vs BillVatDetail (INV-F6)")
class DsfinvkNettingConsistencyTest {
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

    /**
     * (a) A Kassenbuch line 12,00 @ 7 % is a value where the old scale-4-then-2 path diverges:
     * 12.00 / 1.07 = 11.2149… → scale-4 = 11.2150 → re-round to 2 = 11.22 (the old BON_NETTO),
     * while netting once at scale 2 gives 11.21. All THREE KB producers — transactions_vat
     * (BON_NETTO), lines_vat (Σ POS_NETTO) and businesscases (Z_UMS_NETTO) — must agree at 11,21.
     *
     * Red-before/green-after: before the INV-F6 fixes, transactions_vat AND businesscases both
     * used scale-4-then-2 (both 11,22) while lines_vat already netted at scale 2 (11,21). Fixing
     * only transactions_vat would have made businesscases the odd one out — this case pins all three.
     */
    @Test
    fun `all three KB producers net 12,00 at 7 percent to 11,21 (transactions_vat, lines_vat, businesscases)`() {
        transaction {
            val shop = TestData.createShopInfo()
            val employee = TestData.createAdmin()
            val zBon = TestData.createZBonFullDay(shop, employee)
            KassenbuchRepositoryImpl(testKassenbuchDocuments()).createEntry(
                shop = shop,
                employee = employee,
                headerData =
                    KassenbuchDto.KassenbuchData(
                        entryType = KassenbuchEntryType.CASH_EXPENSE,
                        belegNr = "INVF6-A",
                        vorzBetrag = "12.00",
                        belegDatum = "16.07.2026",
                        belegtext = "Netting fixture",
                    ),
                lineItemsData =
                    listOf(
                        KassenbuchDto.LineItemData(vorzBetrag = "12.00", ustSatz = "7", kind = KassenbuchLineKind.STANDARD),
                    ),
            )
            // Reference the zBon so closingFor is not the only linkage (also validates the day window).
            zBon.zbonNummer
        }

        val zip = export()

        // transactions_vat (Bonkopf_USt): one KB key-2 row.
        val vatRows = readRows(zip, "transactions_vat.csv").filter { it["BON_ID"]?.startsWith("KB-") == true }
        val kbKey2Vat = vatRows.single { it["UST_SCHLUESSEL"] == "2" }

        // lines_vat (Bonpos_USt): Σ POS_NETTO over the KB BON_ID's key-2 rows.
        val lineRows = readRows(zip, "lines_vat.csv").filter { it["BON_ID"]?.startsWith("KB-") == true }
        val sumPosNetto =
            lineRows
                .filter { it["UST_SCHLUESSEL"] == "2" }
                .fold(BigDecimal.ZERO) { acc, r -> acc + BigDecimal(r["POS_NETTO"]!!.replace(",", ".")) }

        // businesscases (Z_GV_Typ): the KB CASH_EXPENSE line aggregates as GV_TYP=Auszahlung, key 2.
        val bcRow =
            readRows(zip, "businesscases.csv").single {
                it["GV_TYP"] == DsfinvkExportDto.BusinessCaseTypes.AUSZAHLUNG && it["UST_SCHLUESSEL"] == "2"
            }

        assertEquals("11,21", kbKey2Vat["BON_NETTO"], "transactions_vat BON_NETTO must be single-netted at scale 2, not double-rounded from scale 4")
        assertEquals(
            0,
            sumPosNetto.compareTo(BigDecimal("11.21")),
            "Σ lines_vat POS_NETTO for the KB BON_ID/key must equal 11,21; got $sumPosNetto",
        )
        assertEquals(
            "11,21",
            bcRow["Z_UMS_NETTO"],
            "businesscases Z_UMS_NETTO must be single-netted at scale 2 (not the old double-rounded 11,22) so it agrees with transactions_vat/lines_vat",
        )
        assertEquals(
            BigDecimal(kbKey2Vat["BON_NETTO"]!!.replace(",", ".")).compareTo(sumPosNetto),
            0,
            "transactions_vat BON_NETTO must equal Σ lines_vat POS_NETTO for the KB BON_ID/key",
        )
    }

    /**
     * (b) Two 2,60 items @ 19 % on one bill (≥2 rows share the rate). Per-item netting rounds
     * 2.60 / 1.19 = 2.1848… → 2.18 twice = 4.36; the single-netted target is
     * round(5.20 / 1.19) = round(4.3697…) = 4.37 (== BillVatDetail). Z_UMS_NETTO must be 4,37.
     */
    @Test
    fun `businesscases Z_UMS_NETTO is single-netted 4,37 for two same-rate items`() {
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val cash = TestData.createPaymentType("Cash", isCash = true)
            val tws = TestData.createTableWithSession("INVF6B")
            val category = TestData.createCategory("Netting Cat")
            val product = TestData.createProduct(category.id.value, "Netting Product", "NET001")
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
                    this.billNumber = "INVF6-B"
                    this.billAmount = BigDecimal("5.20")
                    this.paidAmount = BigDecimal("5.20")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }

            repeat(2) {
                OrderItem.new {
                    this.session = session
                    this.bill = bill
                    this.product = product
                    this.unitPrice = BigDecimal("2.60")
                    this.finalPrice = BigDecimal("2.60")
                    this.discount = BigDecimal.ONE // no discount -> gross = finalPrice
                    this.vat = vat19
                    this.itemStatus = ItemStatus.PAID
                    this.takeAway = false
                }
            }

            Payment.new {
                this.bill = bill
                this.paymentType = cash
                this.amount = BigDecimal("5.20")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }
        }

        val zip = export()

        val umsatz =
            readRows(zip, "businesscases.csv").single {
                it["GV_TYP"] == DsfinvkExportDto.BusinessCaseTypes.UMSATZ && it["UST_SCHLUESSEL"] == "1"
            }
        assertEquals("5,20", umsatz["Z_UMS_BRUTTO"], "gross is unchanged by single-netting")
        assertEquals("4,37", umsatz["Z_UMS_NETTO"], "Z_UMS_NETTO must be the single-netted round(5.20/1.19)=4.37, not Σ per-item 4.36")
        assertEquals("0,83", umsatz["Z_UST"], "Z_UST = BRUTTO − NETTO = 5.20 − 4.37")
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
