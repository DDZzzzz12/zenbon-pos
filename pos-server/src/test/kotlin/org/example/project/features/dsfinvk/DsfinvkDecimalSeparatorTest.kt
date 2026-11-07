package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.export.dsfinvk.model.DsfinvkIndexXml
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.models.BillVatDetail
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.order.domain.models.ItemExtras
import org.example.project.features.order.domain.models.OrderItem
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression test for the mixed decimal-separator defect (audit finding C2): most DSFinV-K CSV
 * exporters emitted amount fields via [org.example.project.utils.formatter.toDecimalString]
 * (dot decimal) or wrapped numerics in `escapeString` (quoted + dot), while a few already
 * converted via `DsfinvkCsvUtils.formatDecimal` (comma decimal, unquoted) — so a single export
 * package mixed `"13.43"` and `13,43`, even though `index.xml` declares `DecimalSymbol=,` and
 * types those columns as `Numeric`.
 *
 * The invariant is enforced against `index.xml` itself: every column the descriptor declares
 * `Numeric` (via [DsfinvkIndexXml.tables]) must be emitted UNQUOTED and, when it carries a
 * fractional part, use a comma. Checking against the descriptor (rather than a line-level dot
 * heuristic) catches numerics wrongly wrapped in `escapeString` — which a bare unquoted-dot
 * scan skips (that is the ZI_FAKTOR straggler this test is built to catch).
 */
@DisplayName("DSFinV-K export — decimal separator")
class DsfinvkDecimalSeparatorTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    /** Every amount-bearing CSV the fixture populates; each must be present, non-empty, comma-formatted. */
    private val checkedFiles =
        listOf(
            "transactions.csv",
            "datapayment.csv",
            "businesscases.csv",
            "payment.csv",
            "cash_per_currency.csv",
            "transactions_vat.csv",
            "lines.csv",
            "lines_vat.csv",
            // itemamounts.csv is deliberately header-only since BPOS-8/H12: discounts are
            // separate Bonpos Rabatt lines (Tz. 3.1.1.2 — "muss kein Eintrag erfolgen").
            "cashpointclosing.csv",
            "vat.csv",
            "subitems.csv",
        )

    @Test
    fun `numeric CSV fields are unquoted and use comma decimal separator`() {
        transaction {
            val admin = TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            val cash = TestData.createPaymentType("Cash", isCash = true)
            val vats = TestData.createDefaultVatRates() // [0]=19% Normal, [1]=7% Reduced
            val vat19 = vats[0]
            val category = TestData.createCategory("Dec Cat")
            val drink = TestData.createProduct(category.id.value, "Cola", "COLA")
            val food = TestData.createProduct(category.id.value, "Fries", "FRIES")
            val tws = TestData.createTableWithSession("DEC1")
            val now = currentTimeStamp()

            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-DEC-TEST"
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

            val sale =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "DEC-A"
                    this.billAmount = BigDecimal("16.50")
                    this.paidAmount = BigDecimal("16.50")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }

            // transactions_vat.csv comes from BillVatDetail.
            BillVatDetail.new {
                this.bill = sale
                this.vat = vat19
                this.netAmount = BigDecimal("13.87")
                this.vatAmount = BigDecimal("2.63")
                this.totalAmount = BigDecimal("16.50")
            }

            // Line item WITH a choice-group add-on -> subitems.csv (exercises ZI_FAKTOR="1.00").
            val drinkItem =
                OrderItem.new {
                    this.session = session
                    this.bill = sale
                    this.product = drink
                    this.unitPrice = BigDecimal("12.00")
                    this.finalPrice = BigDecimal("12.00")
                    this.vat = vat19
                    this.itemStatus = ItemStatus.PAID
                    this.takeAway = false
                }
            ItemExtras.new {
                this.orderItem = drinkItem
                this.productExtras = null
                this.optionNameSnapshot = "Extra Shot"
                this.priceDeltaSnapshot = BigDecimal("2.0000")
                this.groupNameSnapshot = "Add-ons"
                this.quantity = 1
            }

            // Line item WITH a discount (discount < 1.00) -> itemamounts.csv (Basispreis + Rabatt).
            OrderItem.new {
                this.session = session
                this.bill = sale
                this.product = food
                this.unitPrice = BigDecimal("5.00")
                this.finalPrice = BigDecimal("4.50")
                this.vat = vat19
                this.itemStatus = ItemStatus.PAID
                this.discount = BigDecimal("0.90")
                this.takeAway = false
            }

            Payment.new {
                this.bill = sale
                this.paymentType = cash
                this.amount = BigDecimal("16.50")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }

            // payment.csv (Z_Zahlart) and cash_per_currency.csv (Z_Waehrungen) derive from
            // the per-closing payment summaries, not the raw Payment rows.
            ZBonPaymentSummary.new {
                this.zBon = zBon
                this.paymentType = cash
                this.currency = "EUR"
                this.amount = BigDecimal("16.50")
            }
        }

        val zip =
            runBlocking {
                service.generateExport(
                    DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"),
                )
            }

        val tablesByUrl = DsfinvkIndexXml.tables().associateBy { it.url }

        checkedFiles.forEach { file ->
            val csv = readZipEntry(zip, file)
            assertNotNull(csv, "$file must be present in the export ZIP")

            val lines = csv.split("\r\n").filter { it.isNotEmpty() }
            // Header + at least one data row: guards against a vacuous pass on an empty file.
            assertTrue(lines.size >= 2, "$file must contain at least one data row (was: ${lines.size} line(s))")

            val table = tablesByUrl[file]
            assertNotNull(table, "index.xml must declare a table for $file")
            val numericColumns =
                table.columns
                    .filter { it.type is DsfinvkIndexXml.Numeric }
                    .map { it.name }
                    .toSet()
            // m19: every Alpha column is declared with a TextEncapsulator ("), so ALL of them must
            // be emitted encapsulated — otherwise "empty" (and every value) has two byte
            // representations in one file, and a strict importer that assumes
            // „declared encapsulator ⇒ all AlphaNumeric encapsulated" misreads the bare ones.
            // POS_ZEILE, INHAUS, P_STORNO and BON_STORNO used to bypass escapeString; this is the
            // mirror of the Numeric-must-be-unquoted rule below.
            val alphaColumns =
                table.columns
                    .filter { it.type is DsfinvkIndexXml.Alpha }
                    .map { it.name }
                    .toSet()
            // m1 (reframed): the GDPdU DTD is explicit about which direction is dangerous —
            // „CAUTION: Results are undefined when importing numeric data with GREATER Accuracy
            // than the Accuracy defined in index.xml … might result in 1000 or an error. This
            // behavior is specific to the implementation." Emitting FEWER decimals than declared
            // is harmless (17,00 read as Accuracy 5 is 17,00000); emitting more is undefined
            // behaviour in the importer. So the invariant is <=, not ==.
            val declaredAccuracy =
                table.columns
                    .mapNotNull { col -> (col.type as? DsfinvkIndexXml.Numeric)?.accuracy?.let { col.name to it } }
                    .toMap()

            val headerNames = splitCsvFields(lines.first()).map { it.first }

            lines.drop(1).forEach { line ->
                val fields = splitCsvFields(line)
                headerNames.forEachIndexed { i, colName ->
                    if (colName in alphaColumns) {
                        val (value, wasQuoted) = fields.getOrElse(i) { "" to false }
                        assertTrue(
                            wasQuoted,
                            "$file column $colName is Alpha per index.xml and must be encapsulated " +
                                "like every other Alpha column; got bare \"$value\" in: $line",
                        )
                    }
                    if (colName !in numericColumns) return@forEachIndexed
                    val (value, wasQuoted) = fields.getOrElse(i) { "" to false }
                    assertTrue(
                        !wasQuoted,
                        "$file column $colName is Numeric per index.xml and must be emitted unquoted; got quoted \"$value\" in: $line",
                    )
                    assertTrue(
                        !Regex("""\d\.\d""").containsMatchIn(value),
                        "$file column $colName is Numeric and must use a comma decimal separator; got \"$value\" in: $line",
                    )
                    declaredAccuracy[colName]?.let { accuracy ->
                        val decimals = value.substringAfter(',', "").length
                        assertTrue(
                            decimals <= accuracy,
                            "$file column $colName is declared <Accuracy>$accuracy</Accuracy> in " +
                                "index.xml but was emitted with $decimals decimals: \"$value\" in: " +
                                "$line — the GDPdU DTD calls importing greater accuracy than " +
                                "declared UNDEFINED behaviour",
                        )
                    }
                }
            }
        }
    }

    /** Reads a single CSV entry (by filename suffix) out of the export ZIP as text, or null. */
    private fun readZipEntry(
        zip: ByteArray,
        name: String,
    ): String? {
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.endsWith(name)) return zis.readBytes().decodeToString()
                entry = zis.nextEntry
            }
        }
        return null
    }

    /**
     * Quote-aware split of a `;`-delimited DSFinV-K CSV row.
     *
     * Returns each field's unquoted content plus whether it was quoted in the source line —
     * numeric fields (per index.xml's `Numeric` type + the "no escapeString on numerics" rule)
     * are emitted unquoted, so the quoted flag is itself part of the invariant being checked.
     */
    private fun splitCsvFields(line: String): List<Pair<String, Boolean>> {
        val fields = mutableListOf<Pair<String, Boolean>>()
        val current = StringBuilder()
        var inQuotes = false
        var wasQuoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    inQuotes = !inQuotes
                    wasQuoted = true
                }
                c == ';' && !inQuotes -> {
                    fields.add(current.toString() to wasQuoted)
                    current.clear()
                    wasQuoted = false
                }
                else -> current.append(c)
            }
            i++
        }
        fields.add(current.toString() to wasQuoted)
        return fields
    }
}
