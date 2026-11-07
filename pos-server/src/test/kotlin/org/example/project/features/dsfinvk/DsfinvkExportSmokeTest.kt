package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.domain.models.ShopInfo
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end smoke test for the full DSFinV-K ZIP export (`generateExport`): the exporter must
 * represent a storno's tender correctly in `datapayment.csv`. Complements the focused [DsfinvkDataPaymentsStornoTest]; this one drives
 * the whole ~25-generator pipeline so a regression in any of them surfaces here.
 */
@DisplayName("DSFinV-K generateExport — smoke")
class DsfinvkExportSmokeTest {
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
    fun `full export with a storno succeeds and negates storno tender`() {
        transaction {
            val admin = TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
            val cash = TestData.createPaymentType("Cash", isCash = true)
            val card = TestData.createPaymentType("Card", isCash = false)
            val tws = TestData.createTableWithSession("SMK1")
            val now = currentTimeStamp()

            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-TEST"
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

            // (a) A normal sale then its storno — exercises the negated datapayment line.
            val saleA =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "SMK-A"
                    this.billAmount = BigDecimal("30.00")
                    this.paidAmount = BigDecimal("30.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            val reverseA =
                Bill.new {
                    this.employeeId = admin
                    this.tableSession = session
                    this.billNumber = "SMK-A-REV"
                    this.billAmount = BigDecimal("-30.00")
                    this.paidAmount = BigDecimal("-30.00")
                    this.tips = BigDecimal.ZERO
                    this.deletedBill = saleA
                    this.startTime = now
                    this.endTime = now
                    this.zBon = zBon
                }
            Payment.new {
                this.bill = saleA
                this.paymentType = card
                this.amount = BigDecimal("30.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.REFUNDED
                this.paymentTime = now
                this.refundedBillId = reverseA
            }
        }

        val zip =
            runBlocking {
                service.generateExport(
                    DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"),
                )
            }

        assertTrue(zip.isNotEmpty(), "export ZIP must not be empty")

        val dataPayment = readZipEntry(zip, "datapayment.csv")
        assertNotNull(dataPayment, "datapayment.csv must be present in the export")
        // The storno reverse bill (SMK-A-REV) must carry a negated card tender line.
        assertTrue(
            dataPayment.lineSequence().any { it.contains("-30,00") },
            "datapayment.csv must contain the negated storno tender (-30,00); got:\n$dataPayment",
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
}
