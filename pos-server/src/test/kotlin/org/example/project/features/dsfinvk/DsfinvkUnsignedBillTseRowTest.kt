package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.domain.models.TseTransaction
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards DSFinV-K compliance item C4: every bill that appears in `transactions.csv` must
 * have a matching row in `transactions_tse.csv`, even when the bill was committed while
 * the TSE was unavailable (`tseTransaction == null`, `tseSigningStatus = FAILED`). Dropping
 * such bills from `transactions_tse.csv` silently loses the fiscal-outage record, which is
 * a §146a AO violation: a fiscally-relevant Beleg would carry no TSE signature and no
 * documented reason why.
 */
@DisplayName("DSFinV-K transactions_tse.csv — unsigned/failed bills still get a row with TSE_TA_FEHLER")
class DsfinvkUnsignedBillTseRowTest {
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
    fun `unsigned bill with FAILED tseSigningStatus appears in transactions_tse csv with a populated TSE_TA_FEHLER`() {
        var bonId = ""
        var signedBonId = ""

        transaction {
            val admin = TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            val cash = TestData.createPaymentType("Cash", isCash = true)
            val tws = TestData.createTableWithSession("TSEFAIL1")
            val now = currentTimeStamp()

            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-TSE-FAIL-TEST"
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

            // Bill committed while the TSE was unavailable: no TseTransaction, terminal
            // signing status FAILED (TseFailureBehavior.FAIL path).
            val sale =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "TSE-FAIL-A"
                    this.billAmount = BigDecimal("10.00")
                    this.paidAmount = BigDecimal("10.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                    this.tseTransaction = null
                    this.tseSigningStatus = TseSigningStatus.FAILED
                }
            Payment.new {
                this.bill = sale
                this.paymentType = cash
                this.amount = BigDecimal("10.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }

            // BON_ID is namespaced per source (audit finding C5): bills use the "B-" prefix.
            bonId = "B-${sale.id.value}"

            // Control bill: genuinely TSE-signed. Pins the tse != null -> null branch so a
            // future change can't start flagging valid signatures with a TSE_TA_FEHLER.
            val signedTse =
                TseTransaction.new {
                    this.tseSerialNumber = "TSE-TEST-SERIAL"
                    this.transactionNumber = 99L
                    this.processType = "Kassenbeleg-V1"
                    this.processDataHash = "hash"
                    this.signature = "signature-bytes"
                    this.signatureCounter = 3L
                    this.signatureAlgorithm = "ecdsa-plain-SHA384"
                    this.startTime = now
                    this.endTime = now
                    this.tseMode = TseMode.NORMAL // a REAL device: SIMULATION is no longer genuine Absicherung (H8)
                }
            val signedSale =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "TSE-OK-A"
                    this.billAmount = BigDecimal("5.00")
                    this.paidAmount = BigDecimal("5.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                    this.tseTransaction = signedTse
                    this.tseSigningStatus = TseSigningStatus.SIGNED
                }
            Payment.new {
                this.bill = signedSale
                this.paymentType = cash
                this.amount = BigDecimal("5.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
            }
            signedBonId = "B-${signedSale.id.value}"
        }

        val zip =
            runBlocking {
                service.generateExport(
                    DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"),
                )
            }

        assertTrue(zip.isNotEmpty(), "export ZIP must not be empty")

        val transactionsCsv = readZipEntry(zip, "transactions.csv")
        assertNotNull(transactionsCsv, "transactions.csv must be present in the export")
        // BON_ID is column index 3 in both transactions.csv and transactions_tse.csv.
        assertNotNull(
            findRowByBonId(transactionsCsv, bonId),
            "unsigned bill's BON_ID must appear in transactions.csv",
        )

        val transactionsTseCsv = readZipEntry(zip, "transactions_tse.csv")
        assertNotNull(transactionsTseCsv, "transactions_tse.csv must be present in the export")

        val row = findRowByBonId(transactionsTseCsv, bonId)
        assertNotNull(row, "unsigned bill's BON_ID must have a matching row in transactions_tse.csv")

        val columns = row.trimEnd().split(";").map { it.trim('"') }
        val tseTaFehler = columns.getOrNull(11)
        assertTrue(
            tseTaFehler?.isNotBlank() == true,
            "unsigned bill must have a transactions_tse row with TSE_TA_FEHLER populated (was '$tseTaFehler')",
        )

        // TSE signature fields must be empty for an unsigned bill; TSE_TANR carries the 0
        // sentinel (real transaction numbers start at 1).
        val tseTanr = columns.getOrNull(5)
        assertEquals("", tseTanr, "unsigned bill has no transaction — TSE_TANR must be empty (STAM-4)")
        val tseTaSig = columns.getOrNull(10)
        assertTrue(tseTaSig.isNullOrBlank(), "unsigned bill must not carry a TSE_TA_SIG (was '$tseTaSig')")

        // The genuinely-signed control bill must have an EMPTY TSE_TA_FEHLER: a valid
        // signature is never an error.
        val signedRow = findRowByBonId(transactionsTseCsv, signedBonId)
        assertNotNull(signedRow, "signed bill's BON_ID must have a matching row in transactions_tse.csv")
        val signedColumns = signedRow.trimEnd().split(";").map { it.trim('"') }
        val signedTseTaFehler = signedColumns.getOrNull(11)
        assertTrue(
            signedTseTaFehler.isNullOrBlank(),
            "genuinely signed bill must have an EMPTY TSE_TA_FEHLER (was '$signedTseTaFehler')",
        )
    }

    /**
     * Finds the CSV data row whose BON_ID (column index 3) exactly equals [bonId]. Matching the
     * parsed column instead of a substring avoids false hits from small integer IDs colliding
     * with Z_NR or amount fields.
     */
    private fun findRowByBonId(
        csv: String,
        bonId: String,
    ): String? =
        csv.lineSequence().drop(1).firstOrNull { line ->
            line
                .trimEnd()
                .split(";")
                .getOrNull(3)
                ?.trim('"') == bonId
        }

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
}
