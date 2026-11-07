package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.models.BillVatDetail
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.table.domain.models.TableSession
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
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * The pre-export "N bills are missing VAT breakdown details" warning counts only Belege that must
 * carry `bill_vat_detail` rows (`BillVatRowExpectation`): a sale stays checked.
 */
@DisplayName("DSFinV-K validate — which bills must carry a VAT breakdown")
class DsfinvkValidateVatBreakdownTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    /** What every seeded Beleg of one test shares: one closed day, one session, one 19 % rate. */
    private class Day(
        val admin: Employee,
        val shop: ShopInfo,
        val zBon: ZBon,
        val session: TableSession,
        val vat19: Vat,
    ) {
        private var nextNumber = 7000

        fun bill(
            amount: String,
            tips: String = "0.00",
            recipient: TipRecipient = TipRecipient.EMPLOYEE,
            configure: Bill.() -> Unit = {},
        ): Bill {
            val now = currentTimeStamp()
            val day = this
            return Bill.new {
                this.employeeId = day.admin
                this.tableSession = day.session
                this.billNumber = (day.nextNumber++).toString()
                this.billAmount = BigDecimal(amount)
                this.paidAmount = BigDecimal(amount) + BigDecimal(tips)
                this.tips = BigDecimal(tips)
                this.tipRecipient = recipient
                this.startTime = now
                this.endTime = now
                this.zBon = day.zBon
                configure()
            }
        }

        fun vatRow(
            bill: Bill,
            gross: String,
            net: String,
        ) {
            val day = this
            BillVatDetail.new {
                this.bill = bill
                this.vat = day.vat19
                this.totalAmount = BigDecimal(gross)
                this.netAmount = BigDecimal(net)
                this.vatAmount = BigDecimal(gross) - BigDecimal(net)
            }
        }
    }

    private fun seed(block: Day.() -> Unit) =
        transaction {
            val admin = TestData.createAdmin()
            val shop = TestData.createShopInfo()
            val zBon = TestData.createZBonFullDay(shop, admin, nummer = 1L)
            val tws = TestData.createTableWithSession("VAT1")
            val session = TableSession.findById(tws.session.id)!!
            val vat19 = TestData.createDefaultVatRates().first { it.vatRate.compareTo(BigDecimal("0.1900")) == 0 }
            Day(admin, shop, zBon, session, vat19).block()
        }

    private fun vatWarnings(): List<String> =
        runBlocking { service.validateExportData("2000-01-01", "2100-01-01") }
            .warnings
            .filter { it.contains("missing VAT breakdown") }

    @Test
    fun `a sales bill without VAT rows still warns`() {
        seed {
            bill("12.00")
        }

        assertEquals(listOf("1 bills are missing VAT breakdown details"), vatWarnings())
    }

    @Test
    fun `a storno of a Forderungsausgleich carries no VAT rows and is not warned about`() {
        seed {
            val sale = bill("23.00")
            vatRow(sale, gross = "23.00", net = "19.33")
            val invoice =
                SalesInvoice.new {
                    this.shop = this@seed.shop
                    this.revenueOrigin = RevenueOrigin.POS_RECEIVABLE
                    this.status = SalesInvoiceStatus.ISSUED
                    this.buyerName = "Catering Nord GmbH"
                    this.issueDate = currentTimeStamp()
                    this.serviceDate = currentTimeStamp()
                    this.billId = sale.id
                }
            val clearing = bill("23.00") { clearsSalesInvoiceId = invoice.id.value }
            bill("-23.00") { deletedBill = clearing }
        }

        assertEquals(emptyList(), vatWarnings())
    }

    @Test
    fun `a storno of a sale must carry the sale's negated VAT rows`() {
        seed {
            val sale = bill("23.00")
            vatRow(sale, gross = "23.00", net = "19.33")
            val reverse = bill("-23.00") { deletedBill = sale }
            vatRow(reverse, gross = "-23.00", net = "-19.33")
            val sale2 = bill("10.00")
            vatRow(sale2, gross = "10.00", net = "8.40")
            bill("-10.00") { deletedBill = sale2 } // rows missing: must be reported
        }

        assertEquals(listOf("1 bills are missing VAT breakdown details"), vatWarnings())
    }
}
