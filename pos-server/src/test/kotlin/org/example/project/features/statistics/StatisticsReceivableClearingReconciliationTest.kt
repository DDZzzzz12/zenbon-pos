package org.example.project.features.statistics

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.features.bill.data.service.MapperBillSummaries
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.models.BillVatDetail
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.payment.domain.models.Payment
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.salesinvoice.domain.models.SalesInvoice
import org.example.project.features.statistics.data.repository.StatisticsRepositoryImpl
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.zBon.database.tables.ZBonTable
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A sale "auf Rechnung" whose outgoing invoice is later collected at the till by a receivable-clearing
 * bill. Turnover arose once — when the invoice was issued — so the Z-Bon's Umsatz leaves the clearing
 * out (DSFinV-K exports it as a Forderungsauflösung), and every other revenue figure must agree:
 * the sales overview, the per-employee Z-Bon rows, the browse groups and the financial overview.
 * The clearing's payment is real money, so the payment breakdowns keep it — and say how much of them
 * is Forderungsausgleich.
 *
 * | Bill    | Employee | billAmount | tip         | tender         |
 * |---------|----------|------------|-------------|----------------|
 * | sale    | admin    | 100.00     | 5.00 OWNER  | Bar 105.00     |
 * | account | waiter   | 50.00      | –           | Rechnung 50.00 |
 * | clear   | waiter   | 50.00      | –           | Karte 50.00    |
 *
 * Umsatz = 100 + 50 + 5 (owner tip) = 155.00; Zahlungsarten = 205.00; Forderungsausgleich = 50.00.
 * Before the fix the overview read 205.00 and the waiter's Z-Bon row 100.00.
 */
@DisplayName("A receivable clearing is in the Zahlungsarten but in no Umsatz figure")
class StatisticsReceivableClearingReconciliationTest {
    private val repo = StatisticsRepositoryImpl(MapperBillSummaries(SalesInvoiceBillInvoiceLink()))

    private val today = currentTimeStamp().date
    private val rangeStart = today.minus(1, DateTimeUnit.DAY).atTime(0, 0, 0)
    private val rangeEnd = today.plus(1, DateTimeUnit.DAY).atTime(23, 59, 59)

    private val waiterNumber = "WAI-RC1"

    @Test
    fun `overview, Z-Bon rows and financial overview all reconcile with the Z-Bon Umsatz`() =
        testApp(setupData = { seedDay() }) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.post("/api/z-bon") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Created, response.status, "body: ${response.bodyAsText()}")

            transaction {
                val report =
                    assertNotNull(ZBon.all().orderBy(ZBonTable.id to SortOrder.DESC).firstOrNull(), "a Z-Bon was created")
                val umsatz = report.totalPosRevenue
                // The fiscal figures this fix must NOT move.
                assertMoney("155.00", umsatz, "Z-Bon Umsatz: the sales and the owner tip, not the clearing")
                assertMoney("50.00", report.totalReceivableCleared, "Z-Bon Forderungsausgleich")
                assertMoney("205.00", report.paymentSummaries.sumOf { it.amount }, "Z-Bon Zahlungsarten keep the clearing")

                // Per-employee Umsatz rows + the owner-tip row add up to the Umsatz.
                val rows = report.employeeSummaries.associateBy { it.employee.employeeNumber }
                assertMoney("50.00", rows.getValue(waiterNumber).totalRevenue, "the collected receivable is not the waiter's turnover")
                assertMoney(umsatz.toPlainString(), rows.values.sumOf { it.totalRevenue } + report.adminTip, "Σ rows + owner tip")
                // …while the waiter's payment breakdown still shows the card money they took.
                assertMoney(
                    "100.00",
                    rows
                        .getValue(waiterNumber)
                        .paymentBreakdown
                        .sumOf { it.amount },
                    "the waiter's payments: Rechnung 50 + Karte 50",
                )

                val overview = repo.getSalesOverviewSummary(rangeStart, rangeEnd, "EUR", null, false)
                assertMoney(umsatz.toPlainString(), BigDecimal(overview.totalRevenue), "overview Umsatz == Z-Bon Umsatz")
                assertMoney("150.00", BigDecimal(overview.billedRevenue), "billed revenue: the two sales")
                assertEquals(2, overview.totalBillCount, "a clearing is not a sale")
                assertMoney("50.00", BigDecimal(overview.receivableCleared), "overview Forderungsausgleich")
                val paymentTotal = overview.revenueByPaymentMethod.sumOf { BigDecimal(it.amount) }
                assertMoney("205.00", paymentTotal, "overview Zahlungsarten keep the clearing payment")
                assertMoney(
                    "50.00",
                    BigDecimal(overview.revenueByPaymentMethod.single { it.paymentTypeDE == "Karte" }.amount),
                    "the card clearing is in the payment breakdown",
                )
                // The difference is explained, not silent.
                assertMoney(
                    paymentTotal.toPlainString(),
                    BigDecimal(overview.totalRevenue) + BigDecimal(overview.receivableCleared),
                    "Zahlungsarten = Umsatz + Forderungsausgleich",
                )

                val groups = repo.getPaginatedEmployeeGroups(rangeStart, rangeEnd).employeeGroups.associateBy { it.employeeNumber }
                assertMoney("50.00", BigDecimal(groups.getValue(waiterNumber).totalRevenue), "browse group: waiter Umsatz")
                assertEquals(1, groups.getValue(waiterNumber).billCount, "browse group: one sale")
                assertMoney(
                    umsatz.toPlainString(),
                    groups.values.sumOf { BigDecimal(it.totalRevenue) },
                    "browse groups add up to the Umsatz",
                )

                val financial = repo.getFinancialOverview(rangeStart, rangeEnd, "EUR", null, false)
                assertMoney(umsatz.toPlainString(), BigDecimal(financial.grossRevenue), "Monatsabschluss Bruttoumsatz == Z-Bon Umsatz")
                assertMoney("50.00", BigDecimal(financial.receivableCleared), "financial Forderungsausgleich")
                assertMoney(
                    "205.00",
                    financial.paymentMethodBreakdown.values.sumOf { BigDecimal(it.totalAmount) },
                    "financial payment breakdown keeps the clearing",
                )
                assertMoney(
                    financial.grossRevenue,
                    financial.dailyFinancialSummary.values.sumOf { BigDecimal(it.grossRevenue) },
                    "the daily series sums to the headline",
                )
            }
        }

    private fun assertMoney(
        expected: String,
        actual: BigDecimal,
        message: String,
    ) = assertEquals(0, BigDecimal(expected).compareTo(actual), "$message: expected $expected, was ${actual.toPlainString()}")

    private fun seedDay() {
        val admin = TestData.createAdmin()
        val waiter = TestData.createCashier(employeeNumber = waiterNumber, username = "waiter-rc1")
        val shop = TestData.createShopInfo()
        val vat19 = TestData.createDefaultVatRates()[0]
        val cash = TestData.createPaymentType("Bar", isCash = true)
        val onAccount = TestData.createPaymentType("Rechnung", isCash = false)
        val card = TestData.createPaymentType("Karte", isCash = false)
        val tws = TestData.createTableWithSession("RCR1")

        transaction {
            exec("CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK AS 'long pgAdvisoryXactLock(long key) { return key; }'")
            val receivableTender = PaymentType.findById(onAccount.id)!!.apply { createsReceivable = true }
            val now = currentTimeStamp()
            Terminal.new {
                deviceId = "unknown"
                clientId = "TERM-TEST"
                registeredAt = now
                lastSeenAt = now
            }
            val session = TableSession.findById(tws.session.id)!!
            val table = RTable.findById(tws.table.id)!!
            session.completed = true
            session.endTime = now
            table.currentSession = null
            table.tableStatus = TableStatus.FREE

            fun sale(
                employee: Employee,
                number: String,
                amount: String,
                tip: String,
                tender: PaymentType,
                vat: Vat,
                net: String,
                vatAmount: String,
            ): Bill {
                val paid = BigDecimal(amount) + BigDecimal(tip)
                val bill =
                    Bill.new {
                        this.employeeId = employee
                        this.tableSession = session
                        this.billNumber = number
                        this.billAmount = BigDecimal(amount)
                        this.paidAmount = paid
                        this.tips = BigDecimal(tip)
                        this.tipRecipient = TipRecipient.OWNER
                        this.startTime = now
                        this.endTime = now
                    }
                BillVatDetail.new {
                    this.bill = bill
                    this.vat = vat
                    this.netAmount = BigDecimal(net)
                    this.vatAmount = BigDecimal(vatAmount)
                    this.totalAmount = paid
                }
                Payment.new {
                    this.bill = bill
                    this.paymentType = tender
                    this.amount = paid
                    this.currency = "EUR"
                    this.paymentStatus = PaymentStatus.COMPLETED
                    this.paymentTime = now
                    this.createsReceivable = tender.createsReceivable
                }
                return bill
            }

            // The admin's cash sale with a taxable owner tip (TrinkgeldAG, folded into the 19 % row).
            sale(admin, "RCR-SALE", "100.00", "5.00", cash, vat19, "88.24", "16.76")
            // The waiter's sale "auf Rechnung": the turnover of the invoice below.
            val accountBill = sale(waiter, "RCR-ACC", "50.00", "0.00", receivableTender, vat19, "42.02", "7.98")

            val invoice =
                SalesInvoice.new {
                    this.shop = shop
                    this.revenueOrigin = RevenueOrigin.POS_RECEIVABLE
                    this.status = SalesInvoiceStatus.ISSUED
                    this.buyerName = "Catering Nord GmbH"
                    this.issueDate = now
                    this.serviceDate = now
                    this.billId = accountBill.id
                }

            // The waiter collects the invoice by card — shaped as SalesInvoiceServiceImpl.settleAtTill
            // writes it: no items, no VAT rows, no tip.
            val clearingBill =
                Bill.new {
                    this.employeeId = waiter
                    this.tableSession = session
                    this.billNumber = "RCR-CLR"
                    this.billAmount = BigDecimal("50.00")
                    this.paidAmount = BigDecimal("50.00")
                    this.tips = BigDecimal.ZERO
                    this.startTime = now
                    this.endTime = now
                    this.clearsSalesInvoiceId = invoice.id.value
                }
            Payment.new {
                this.bill = clearingBill
                this.paymentType = card
                this.amount = BigDecimal("50.00")
                this.currency = "EUR"
                this.paymentStatus = PaymentStatus.COMPLETED
                this.paymentTime = now
                this.createsReceivable = false
            }
        }
    }
}
