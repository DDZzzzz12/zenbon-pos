package org.example.project.features.monthlyclose

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.bill.StornoReason
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.payment.database.tables.PaymentTable
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * admin18 NOTE-20 / NOTE-21: two figures on the Monatsabschluss that disagreed with no explanation.
 *
 *  - NOTE-20: the KPI "refunds" leaves Zahlartwechsel Stornos out (they refund nothing), the Z-Bon
 *    roll-up's stored "refunds" keeps them. The roll-up now carries the stored "davon
 *    Zahlartwechsel", so roll-up refunds − rebookStornos == the KPI.
 *  - NOTE-21: a pre-1.1.0 closing stored `totalPosRevenue = Σ billAmount` — promotions included —
 *    while the bill-derived Umsatz KPI nets them. The roll-up keeps the stored figure and adds what
 *    the closing's own bills give plus the difference; the stored closing is not rewritten.
 */
@DisplayName("Monatsabschluss: Z-Bon roll-up reconciles with the KPIs")
class MonthlyCloseRollupReconciliationTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val at = LocalDateTime(2026, 6, 10, 12, 0, 0)
    private val period = "periodStart=2026-06-01T00:00:00&periodEnd=2026-07-01T00:00:00&comparePreviousPeriod=false&cadence=MANUAL"

    private class Ctx(
        val shop: ShopInfo,
        val employee: Employee,
        val session: TableSession,
        val cash: PaymentType,
    )

    private var billCounter = 0

    private fun seedZBon(
        ctx: Ctx,
        number: Long,
        start: LocalDateTime,
        end: LocalDateTime,
        storedRevenue: String,
        refunds: String = "0",
        rebookStornos: String = "0",
    ): ZBon =
        ZBon.new {
            this.shopInfo = ctx.shop
            this.employee = ctx.employee
            this.startTime = start
            this.endTime = end
            this.cancelledAmount = BigDecimal.ZERO
            this.totalPosRevenue = BigDecimal(storedRevenue)
            this.adminTip = BigDecimal.ZERO
            this.openingBalance = BigDecimal.ZERO
            this.expenses = BigDecimal.ZERO
            this.cashRevenue = BigDecimal.ZERO
            this.closingBalance = BigDecimal.ZERO
            this.refundAmount = BigDecimal(refunds)
            this.rebookStornoAmount = BigDecimal(rebookStornos)
            this.createdAt = currentTimeStamp()
            this.zbonNummer = number
        }

    /** A bill closed into [zBon]; a positive [paid] amount also gets a cash payment of that amount. */
    private fun bill(
        ctx: Ctx,
        zBon: ZBon,
        billAmount: String,
        paid: String,
        promotion: String = "0",
        configure: Bill.() -> Unit = {},
    ): Bill {
        val b =
            Bill.new {
                this.employeeId = ctx.employee
                this.tableSession = ctx.session
                this.billNumber = "RC-${++billCounter}"
                this.billAmount = BigDecimal(billAmount)
                this.promotionAmount = BigDecimal(promotion)
                this.paidAmount = BigDecimal(paid)
                this.startTime = at
                this.endTime = at
                this.isTraining = false
                this.zBon = zBon
                configure()
            }
        if (BigDecimal(paid).signum() > 0) {
            PaymentTable.insert {
                it[billId] = b.id
                it[paymentTypeId] = ctx.cash.id
                it[amount] = BigDecimal(paid)
                it[paymentStatus] = PaymentStatus.COMPLETED
                it[paymentTime] = at
                it[currency] = "EUR"
            }
        }
        return b
    }

    /** A cancelled original of [amount] and its Storno; [reason] null = an ordinary refund. */
    private fun stornoPair(
        ctx: Ctx,
        zBon: ZBon,
        amount: String,
        reason: StornoReason?,
    ) {
        val original = bill(ctx, zBon, amount, "0") { deletingReason = "Storno" }
        bill(ctx, zBon, "-$amount", "-$amount") {
            deletedBill = original
            stornoReason = reason
        }
    }

    private fun ctx(): Ctx {
        val shop = TestData.createShopInfo()
        TestData.createManager()
        val tws = TestData.createTableWithSession("RC")
        val cash = TestData.createPaymentType(name = "BAR", isCash = true)
        return transaction { Ctx(shop, tws.session.employeeId, tws.session, cash) }
    }

    private suspend fun close(client: io.ktor.client.HttpClient): MonthlyCloseDto.MonthlyCloseResponseDto {
        val token = TestAuthHelper.loginAs(client, "manager")
        val response = client.get("/api/v1/monthly-close?$period") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    @Test
    fun `a pre-upgrade closing with a promotion shows its stored Umsatz, the bills' Umsatz and the difference`() {
        testApp(
            setupData = {
                val c = ctx()
                transaction {
                    // Legacy (≤ v1.0.10): stored Σ billAmount = 20.00, the 5.00 promotion inside it.
                    val legacy =
                        seedZBon(c, 135, LocalDateTime(2026, 6, 1, 8, 0), LocalDateTime(2026, 6, 5, 23, 0), storedRevenue = "20.00")
                    bill(c, legacy, billAmount = "20.00", paid = "15.00", promotion = "5.00")
                    // Current rule: stored Σ(billAmount − promotionAmount) = 8.00 — the negative control.
                    val current =
                        seedZBon(c, 154, LocalDateTime(2026, 6, 5, 23, 0), LocalDateTime(2026, 6, 20, 23, 0), storedRevenue = "8.00")
                    bill(c, current, billAmount = "10.00", paid = "8.00", promotion = "2.00")
                }
            },
        ) { client ->
            val dto = close(client)
            val rows = dto.zBonRollup.rows.associateBy { it.zBonNumber }

            assertEquals("20.00", rows.getValue("135").grossRevenue, "the stored closing is shown as stored")
            assertEquals("15.00", rows.getValue("135").revenueFromBills)
            assertEquals("5.00", rows.getValue("135").storedRevenueDifference)

            assertEquals("8.00", rows.getValue("154").grossRevenue)
            assertEquals("8.00", rows.getValue("154").revenueFromBills)
            assertEquals("0.00", rows.getValue("154").storedRevenueDifference, "a current closing agrees with its bills")

            val totals = dto.zBonRollup.totals
            assertEquals("28.00", totals.grossRevenue)
            assertEquals("23.00", totals.revenueFromBills)
            assertEquals("5.00", totals.storedRevenueDifference)
            // The tile is bill-derived and equals the roll-up's bill-derived Umsatz, not the stored one.
            assertEquals(totals.revenueFromBills, dto.kpis.single { it.key == "grossRevenue" }.value)
        }
    }

    @Test
    fun `the refunds KPI is the roll-up's refunds less its Zahlartwechsel Stornos`() {
        testApp(
            setupData = {
                val c = ctx()
                transaction {
                    val z =
                        seedZBon(
                            c,
                            154,
                            LocalDateTime(2026, 6, 1, 8, 0),
                            LocalDateTime(2026, 6, 20, 23, 0),
                            storedRevenue = "10.00",
                            refunds = "14.00",
                            rebookStornos = "10.00",
                        )
                    stornoPair(c, z, "10.00", StornoReason.ZAHLARTWECHSEL)
                    bill(c, z, billAmount = "10.00", paid = "10.00") // the Neubuchung
                    stornoPair(c, z, "4.00", reason = null)
                }
            },
        ) { client ->
            val dto = close(client)
            val totals = dto.zBonRollup.totals
            assertEquals("14.00", totals.refunds, "the fiscal Storno total, Zahlartwechsel included")
            assertEquals("10.00", totals.rebookStornos)
            val row = dto.zBonRollup.rows.single()
            assertEquals("10.00", row.rebookStornos)
            val kpiRefunds = dto.kpis.single { it.key == "refunds" }.value
            assertEquals("4.00", kpiRefunds)
            assertEquals(totals.refunds.toBigDecimal() - totals.rebookStornos.toBigDecimal(), kpiRefunds.toBigDecimal())
            assertEquals("0.00", totals.storedRevenueDifference, "Stornos and the Neubuchung reconcile")
        }
    }

    @Test
    fun `without a Zahlartwechsel the KPI and the roll-up report the same refunds`() {
        testApp(
            setupData = {
                val c = ctx()
                transaction {
                    val z =
                        seedZBon(
                            c,
                            154,
                            LocalDateTime(2026, 6, 1, 8, 0),
                            LocalDateTime(2026, 6, 20, 23, 0),
                            storedRevenue = "0.00",
                            refunds = "4.00",
                        )
                    stornoPair(c, z, "4.00", reason = null)
                }
            },
        ) { client ->
            val dto = close(client)
            assertEquals("0.00", dto.zBonRollup.totals.rebookStornos)
            assertEquals("4.00", dto.zBonRollup.totals.refunds)
            assertEquals("4.00", dto.kpis.single { it.key == "refunds" }.value)
        }
    }
}
