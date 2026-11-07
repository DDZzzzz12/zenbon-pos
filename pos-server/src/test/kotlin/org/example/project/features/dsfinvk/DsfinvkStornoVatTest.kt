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
 * Pins the DSFinV-K `transactions_vat.csv` (Bonkopf_USt) storno path (audit finding H4).
 *
 * A storno reverse bill (`deletedBill != null`) carries no `BillVatDetail` rows of its own — the
 * VAT breakdown lives on the original bill. Previously `generateBonkopfUst` queried the reverse
 * bill's own (empty) rows, leaving `transactions_vat.csv` empty for that BON_ID while
 * `transactions.csv` (`UMS_BRUTTO`) and `lines_vat.csv` (`POS_BRUTTO`) were correctly negated —
 * a three-way per-BON_ID disagreement. This test builds a simple sale (single line item, no
 * discount, no promotion) with one 19% `BillVatDetail`, then a full storno reverse bill, and
 * asserts the reverse BON_ID's `transactions_vat.csv` rows are present, equal the NEGATED
 * original VAT breakdown, and reconcile with the reverse bill's own `UMS_BRUTTO`.
 */
@DisplayName("DSFinV-K transactions_vat.csv — storno VAT breakdown")
class DsfinvkStornoVatTest {
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
    fun `reverse bill's transactions_vat equals negated original and reconciles to UMS_BRUTTO`() {
        val reverseBillId: Long =
            transaction {
                val admin = TestData.createAdmin()
                val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
                val cash = TestData.createPaymentType("Cash", isCash = true)
                val vats = TestData.createDefaultVatRates() // [0]=19% Normal, [1]=7% Reduced
                val vat19 = vats[0]
                val category = TestData.createCategory("SVAT Cat")
                val drink = TestData.createProduct(category.id.value, "Espresso", "ESPR")
                val tws = TestData.createTableWithSession("SVAT1")
                val now = currentTimeStamp()

                Terminal.new {
                    deviceId = "unknown"
                    clientId = "TERM-SVAT-TEST"
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

                // Simple sale — single line, NO discount, NO promotion, NO tip — so UMS_BRUTTO ==
                // billAmount exactly and reconciliation stays clean of the deferred H3b
                // line-discount double-subtraction bug.
                val originalBill =
                    Bill.new {
                        this.employeeId = admin
                        this.tableSession = session
                        this.billNumber = "SVAT-ORIG"
                        this.billAmount = BigDecimal("11.90")
                        this.paidAmount = BigDecimal("11.90")
                        this.tips = BigDecimal.ZERO
                        this.startTime = now
                        this.endTime = now
                        this.zBon = zBon
                    }

                BillVatDetail.new {
                    this.bill = originalBill
                    this.vat = vat19
                    this.netAmount = BigDecimal("10.00")
                    this.vatAmount = BigDecimal("1.90")
                    this.totalAmount = BigDecimal("11.90")
                }

                OrderItem.new {
                    this.session = session
                    this.bill = originalBill
                    this.product = drink
                    this.unitPrice = BigDecimal("11.90")
                    this.finalPrice = BigDecimal("11.90")
                    this.vat = vat19
                    this.itemStatus = ItemStatus.PAID
                    this.takeAway = false
                }

                ZBonPaymentSummary.new {
                    this.zBon = zBon
                    this.paymentType = cash
                    this.currency = "EUR"
                    this.amount = BigDecimal("11.90")
                }

                // Full storno: reverse bill has NO BillVatDetail and NO OrderItem rows of its own —
                // resolveOrderItems()/generateLineVats() already source items from the original bill.
                val reverseBill =
                    Bill.new {
                        this.employeeId = admin
                        this.tableSession = session
                        this.billNumber = "SVAT-REV"
                        this.billAmount = BigDecimal("-11.90")
                        this.paidAmount = BigDecimal("-11.90")
                        this.tips = BigDecimal.ZERO
                        this.deletedBill = originalBill
                        this.startTime = now
                        this.endTime = now
                        this.zBon = zBon
                    }

                Payment.new {
                    this.bill = originalBill
                    this.paymentType = cash
                    this.amount = BigDecimal("11.90")
                    this.currency = "EUR"
                    this.paymentStatus = PaymentStatus.REFUNDED
                    this.paymentTime = now
                    this.refundedBillId = reverseBill
                }

                reverseBill.id.value
            }

        val zip =
            runBlocking {
                service.generateExport(
                    DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"),
                )
            }

        val reverseBonId = "B-$reverseBillId"

        val transactionsCsv = readZipEntry(zip, "transactions.csv")
        assertNotNull(transactionsCsv, "transactions.csv must be present in the export")
        val transactionsLines = transactionsCsv.split("\r\n").filter { it.isNotEmpty() }
        val transactionsHeader = splitCsvFields(transactionsLines.first()).map { it.first }
        val bonIdCol = transactionsHeader.indexOf("BON_ID")
        val umsBruttoCol = transactionsHeader.indexOf("UMS_BRUTTO")
        val reverseTransactionRow =
            transactionsLines
                .drop(1)
                .map { splitCsvFields(it) }
                .single { it[bonIdCol].first == reverseBonId }
        val umsBrutto = parseGerman(reverseTransactionRow[umsBruttoCol].first)
        assertEquals(0, umsBrutto.compareTo(BigDecimal("-11.90")), "reverse bill UMS_BRUTTO must be -11.90; got $umsBrutto")

        val transactionsVatCsv = readZipEntry(zip, "transactions_vat.csv")
        assertNotNull(transactionsVatCsv, "transactions_vat.csv must be present in the export")
        val vatLines = transactionsVatCsv.split("\r\n").filter { it.isNotEmpty() }
        val vatHeader = splitCsvFields(vatLines.first()).map { it.first }
        val vatBonIdCol = vatHeader.indexOf("BON_ID")
        val bonBruttoCol = vatHeader.indexOf("BON_BRUTTO")
        val bonNettoCol = vatHeader.indexOf("BON_NETTO")
        val bonUstCol = vatHeader.indexOf("BON_UST")

        val reverseVatRows =
            vatLines
                .drop(1)
                .map { splitCsvFields(it) }
                .filter { it[vatBonIdCol].first == reverseBonId }

        // THE core H4 assertion: the reverse bill must NOT have an empty transactions_vat block.
        assertTrue(
            reverseVatRows.isNotEmpty(),
            "reverse BON_ID=$reverseBonId must have transactions_vat rows (was empty before the H4 fix)",
        )

        val sumBrutto = reverseVatRows.sumOf { parseGerman(it[bonBruttoCol].first) }
        val sumNetto = reverseVatRows.sumOf { parseGerman(it[bonNettoCol].first) }
        val sumUst = reverseVatRows.sumOf { parseGerman(it[bonUstCol].first) }

        // Must equal the NEGATED original VAT breakdown (11.90 / 10.00 / 1.90).
        assertEquals(
            0,
            sumBrutto.compareTo(BigDecimal("-11.90")),
            "Σ transactions_vat.BON_BRUTTO for reverse BON_ID must equal -11.90 (negated original); got $sumBrutto",
        )
        assertEquals(
            0,
            sumNetto.compareTo(BigDecimal("-10.00")),
            "Σ transactions_vat.BON_NETTO for reverse BON_ID must equal -10.00 (negated original); got $sumNetto",
        )
        assertEquals(
            0,
            sumUst.compareTo(BigDecimal("-1.90")),
            "Σ transactions_vat.BON_UST for reverse BON_ID must equal -1.90 (negated original); got $sumUst",
        )

        // Must reconcile with the reverse bill's own UMS_BRUTTO (three-way agreement, H4).
        assertEquals(
            0,
            sumBrutto.compareTo(umsBrutto),
            "Σ transactions_vat.BON_BRUTTO must equal transactions.UMS_BRUTTO for the same BON_ID; got $sumBrutto vs $umsBrutto",
        )

        val linesVatCsv = readZipEntry(zip, "lines_vat.csv")
        assertNotNull(linesVatCsv, "lines_vat.csv must be present in the export")
        val lvLines = linesVatCsv.split("\r\n").filter { it.isNotEmpty() }
        val lvHeader = splitCsvFields(lvLines.first()).map { it.first }
        val lvBonIdCol = lvHeader.indexOf("BON_ID")
        val lvPosBruttoCol = lvHeader.indexOf("POS_BRUTTO")
        val lvSum =
            lvLines
                .drop(1)
                .map { splitCsvFields(it) }
                .filter { it[lvBonIdCol].first == reverseBonId }
                .sumOf { parseGerman(it[lvPosBruttoCol].first) }
        assertEquals(
            0,
            sumBrutto.compareTo(lvSum),
            "Σ transactions_vat.BON_BRUTTO must equal Σ lines_vat.POS_BRUTTO for the same BON_ID; got $sumBrutto vs $lvSum",
        )
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

    /** Parses a comma-decimal DSFinV-K numeric field into a BigDecimal. */
    private fun parseGerman(value: String): BigDecimal = BigDecimal(value.replace(",", "."))

    /**
     * Quote-aware split of a `;`-delimited DSFinV-K CSV row.
     *
     * Returns each field's unquoted content plus whether it was quoted in the source line.
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
