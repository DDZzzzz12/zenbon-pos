package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.models.BillVatDetail
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.order.domain.models.ItemExtras
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.payment.domain.models.Vat
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
import kotlin.test.assertTrue

/**
 * Pins the DSFinV-K brutto=netto+ust rounding invariant (audit finding M1).
 *
 * Every emitted VAT triple (BRUTTO/NETTO/UST) is independently `setScale(2, HALF_UP)` at emit
 * time. Since NETTO and UST are each derived from BRUTTO via a scale-4 division
 * (`net = gross / (1+rate)`, `vat = gross - net`), an exact 4th-decimal tie (e.g. `net` ending in
 * `...X050`) rounds NETTO and UST up independently while BRUTTO — already a clean 2-decimal
 * amount — rounds to itself, so `NETTO + UST` can exceed `BRUTTO` by 0.01.
 *
 * Fixture: a custom 11.05% VAT rate (chosen so `10.00 / 1.1105` rounds to exactly `9.0050` at
 * scale 4) applied to a EUR 10.00 line, which triggers the drift in every division-based
 * producer (lines_vat, itemamounts, subitems, businesscases) simultaneously — before the fix:
 * NETTO 9.0050 -> 9.01, UST 0.9950 -> 1.00, sum 10.01 != BRUTTO 10.00. `transactions_vat.csv`
 * sources straight from a persisted `BillVatDetail`, seeded with the equivalent
 * 9.0050/0.9950/10.0000 triple to trigger the same drift there.
 */
@DisplayName("DSFinV-K export — brutto=netto+ust rounding invariant (M1)")
class DsfinvkRoundingInvariantTest {
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
    fun `every emitted VAT row satisfies BRUTTO equals NETTO plus UST at scale 2`() {
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val cash = TestData.createPaymentType("Cash", isCash = true)
            val tws = TestData.createTableWithSession("ROUND1")
            val category = TestData.createCategory("Rounding Cat")
            val product = TestData.createProduct(category.id.value, "Rounding Product", "RND001")
            val now = currentTimeStamp()

            // Custom VAT rate chosen so that 10.00 / (1 + rate), rounded HALF_UP to scale 4,
            // lands exactly on an X.XY50 tie -> independent rounding of NETTO/UST drifts by 0.01
            // against BRUTTO.
            val vatDrift =
                Vat.new("DRIFT") {
                    this.vatClass = "Drift"
                    this.vatRate = BigDecimal("0.1105")
                }

            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-ROUND-TEST"
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

            val bill =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "ROUND-A"
                    this.billAmount = BigDecimal("9.00")
                    this.paidAmount = BigDecimal("9.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }

            // transactions_vat.csv (Bonkopf_USt) is sourced directly from BillVatDetail, not
            // re-derived from order items — seed the same drift triple explicitly.
            BillVatDetail.new {
                this.bill = bill
                this.vat = vatDrift
                this.netAmount = BigDecimal("9.0050")
                this.vatAmount = BigDecimal("0.9950")
                this.totalAmount = BigDecimal("10.0000")
            }

            // unitPrice=10.00 with a discount (finalPrice=9.00): lines_vat.POS_BRUTTO,
            // itemamounts.PF_BRUTTO (Basispreis) and businesscases.Z_UMS_BRUTTO all use the
            // undiscounted unitPrice (10.00) as the gross for this item, so the same
            // 11.05% rate reproduces the drift in each of those producers too.
            val item =
                OrderItem.new {
                    this.session = session
                    this.bill = bill
                    this.product = product
                    this.unitPrice = BigDecimal("10.00")
                    this.finalPrice = BigDecimal("9.00")
                    this.discount = BigDecimal("0.90")
                    this.vat = vatDrift
                    this.itemStatus = ItemStatus.PAID
                    this.takeAway = false
                }

            // subitems.csv (ZI_BASISPREIS_*) inherits the parent item's VAT rate -> same drift.
            ItemExtras.new {
                this.orderItem = item
                this.productExtras = null
                this.optionNameSnapshot = "Drift Extra"
                this.priceDeltaSnapshot = BigDecimal("10.0000")
                this.groupNameSnapshot = "Add-ons"
                this.quantity = 1
            }

            Payment.new {
                this.bill = bill
                this.paymentType = cash
                this.amount = BigDecimal("9.00")
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

        val checks =
            listOf(
                Triple("businesscases.csv", "Z_UMS_BRUTTO", "Z_UMS_NETTO") to "Z_UST",
                Triple("transactions_vat.csv", "BON_BRUTTO", "BON_NETTO") to "BON_UST",
                Triple("lines_vat.csv", "POS_BRUTTO", "POS_NETTO") to "POS_UST",
                // itemamounts.csv is header-only since BPOS-8/H12 (separate-Rabatt-line form).
                Triple("subitems.csv", "ZI_BASISPREIS_BRUTTO", "ZI_BASISPREIS_NETTO") to "ZI_BASISPREIS_UST",
            )

        checks.forEach { (fileAndCols, ustCol) ->
            val (file, bruttoCol, nettoCol) = fileAndCols
            val csv = readZipEntry(zip, file)
            requireNotNull(csv) { "$file must be present in the export ZIP" }

            val rows = parseCsv(csv)
            assertTrue(rows.isNotEmpty(), "$file must have at least one data row for this fixture")

            rows.forEach { row ->
                val brutto = parseAmount(row[bruttoCol]!!)
                val netto = parseAmount(row[nettoCol]!!)
                val ust = parseAmount(row[ustCol]!!)
                val sum = netto + ust
                assertTrue(
                    brutto.compareTo(sum) == 0,
                    "$file: $bruttoCol ($brutto) must equal $nettoCol + $ustCol ($netto + $ust = $sum); row=$row",
                )
            }
        }
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
