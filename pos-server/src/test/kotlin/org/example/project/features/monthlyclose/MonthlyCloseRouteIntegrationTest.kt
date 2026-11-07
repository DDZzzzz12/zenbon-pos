package org.example.project.features.monthlyclose

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.features.bill.database.tables.BillVatDetailTable
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.payment.database.tables.PaymentTable
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.testutils.testKassenbuchDocuments
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@DisplayName("GET /api/v1/monthly-close integration tests")
class MonthlyCloseRouteIntegrationTest {
    private val responseJson = Json { ignoreUnknownKeys = true }

    // -------------------------------------------------------------------------
    // Inline helpers
    // -------------------------------------------------------------------------

    /**
     * Seeds a minimal ZBon row directly.
     *
     * Requires a ShopInfo and Employee to already exist. All monetary fields
     * default to zero; only startTime/endTime/zbonNummer are configurable here.
     */
    private fun seedZBon(
        shop: ShopInfo,
        employee: org.example.project.features.employee.domain.models.Employee,
        startTime: LocalDateTime,
        endTime: LocalDateTime,
        zbonNummer: Long,
    ): ZBon =
        transaction {
            ZBon.new {
                this.shopInfo = shop
                this.employee = employee
                this.startTime = startTime
                this.endTime = endTime
                this.cancelledAmount = BigDecimal.ZERO
                this.totalPosRevenue = BigDecimal.ZERO
                this.adminTip = BigDecimal.ZERO
                this.openingBalance = BigDecimal.ZERO
                this.expenses = BigDecimal.ZERO
                this.cashRevenue = BigDecimal.ZERO
                this.closingBalance = BigDecimal.ZERO
                this.createdAt = currentTimeStamp()
                this.zbonNummer = zbonNummer
            }
        }

    /**
     * Seeds a minimal closed Bill row directly.
     *
     * Requires an employee and a TableSession. The bill is NOT training-mode so
     * it is visible to [existsClosedBillsBetween].
     */
    private fun seedBill(
        employee: org.example.project.features.employee.domain.models.Employee,
        tableSession: org.example.project.features.table.domain.models.TableSession,
        startTime: LocalDateTime,
        endTime: LocalDateTime,
        billNumber: String,
    ) = transaction {
        org.example.project.features.bill.domain.models.Bill.new {
            this.employeeId = employee
            this.tableSession = tableSession
            this.billNumber = billNumber
            this.billAmount = BigDecimal("10.0000")
            this.paidAmount = BigDecimal("10.0000")
            this.startTime = startTime
            this.endTime = endTime
            this.tseSigningStatus = org.example.project.core.domain.enums.tse.TseSigningStatus.SIGNED
            this.isTraining = false
        }
    }

    // -------------------------------------------------------------------------
    // E2E-2026-09-18 #9 — the Monatsabschluss is shop-wide
    // -------------------------------------------------------------------------

    @Test
    fun `KPIs count every employee's bills, not only the requester's`() {
        val at = LocalDateTime(2026, 6, 10, 12, 0, 0)
        testApp(
            setupData = {
                TestData.createShopInfo()
                TestData.createManager()
                val vat = TestData.createDefaultVatRates().first { it.vatRate == BigDecimal("0.1900") }
                val cash = TestData.createPaymentType(name = "BAR", isCash = true)
                // The session's owner is a cashier, not the manager who asks for the close.
                val tws = TestData.createTableWithSession("MCW")
                transaction {
                    val bill =
                        org.example.project.features.bill.domain.models.Bill.new {
                            this.employeeId = tws.session.employeeId
                            this.tableSession = tws.session
                            this.billNumber = "MCW-1"
                            this.billAmount = BigDecimal("11.9000")
                            this.paidAmount = BigDecimal("11.9000")
                            this.startTime = at
                            this.endTime = at
                            this.isTraining = false
                        }
                    PaymentTable.insert {
                        it[billId] = bill.id
                        it[paymentTypeId] = cash.id
                        it[amount] = BigDecimal("11.9000")
                        it[paymentStatus] = PaymentStatus.COMPLETED
                        it[paymentTime] = at
                        it[currency] = "EUR"
                    }
                    BillVatDetailTable.insert {
                        it[billId] = bill.id
                        it[vatId] = vat.id
                        it[netAmount] = BigDecimal("10.0000")
                        it[vatAmount] = BigDecimal("1.9000")
                        it[totalAmount] = BigDecimal("11.9000")
                    }
                }
            },
        ) { client ->
            val token = TestAuthHelper.loginAs(client, "manager")
            val response =
                client.get(
                    "/api/v1/monthly-close?periodStart=2026-06-01T00:00:00&periodEnd=2026-07-01T00:00:00" +
                        "&comparePreviousPeriod=false&cadence=MANUAL",
                ) { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val dto = responseJson.decodeFromString<MonthlyCloseDto.MonthlyCloseResponseDto>(response.bodyAsText())

            val kpi = dto.kpis.associateBy { it.key }
            assertEquals("11.90", kpi.getValue("grossRevenue").value)
            assertEquals("1", kpi.getValue("billCount").value)
            assertEquals("10.00", kpi.getValue("netRevenue").value)
            assertTrue(dto.paymentBreakdown.containsKey("BAR"), "payments: ${dto.paymentBreakdown.keys}")
        }
    }

    @Test
    fun `the Kassenbuch closing is the fiscal book's balance, not the book that booked last`() {
        // createEntry stamps createdAt = now, so the window brackets today.
        val now = currentTimeStamp()
        val from = LocalDateTime(now.year, now.month, now.day, 0, 0, 0)
        val to = LocalDateTime(now.year, now.month, now.day, 23, 59, 59)
        testApp(
            setupData = {
                transaction {
                    val shop = TestData.createShopInfo()
                    val manager = TestData.createManager()
                    val tresor = CashBookRepositoryImpl().create(shop, "Tresor", isFiscal = false, datevAccount = "1010")

                    fun entry(
                        amount: String,
                        text: String,
                        belegNr: String,
                        bookId: Long?,
                    ) = KassenbuchRepositoryImpl(testKassenbuchDocuments()).createEntry(
                        shop = shop,
                        employee = manager,
                        headerData =
                            KassenbuchDto.KassenbuchData(
                                entryType = KassenbuchEntryType.CASH_SALE,
                                belegNr = belegNr,
                                vorzBetrag = amount,
                                belegDatum = "01.01.2026",
                                belegtext = text,
                                cashBookId = bookId,
                            ),
                        lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = amount, ustSatz = "19")),
                    )
                    entry("100.00", "Ladenkasse-Eintrag", "MC-1", null) // null = the fiscal Ladenkasse
                    entry("7.00", "Tresor-Eintrag", "MC-2", tresor.id.value) // later, another book
                }
            },
        ) { client ->
            val token = TestAuthHelper.loginAs(client, "manager")
            val response =
                client.get(
                    "/api/v1/monthly-close?periodStart=$from&periodEnd=$to&comparePreviousPeriod=false&cadence=MANUAL",
                ) { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val dto = responseJson.decodeFromString<MonthlyCloseDto.MonthlyCloseResponseDto>(response.bodyAsText())

            assertEquals("100.00", dto.kassenbuchRollup.closingBalance)
            assertEquals("100.00", dto.kpis.single { it.key == "kassenbuchClosing" }.value)
            assertTrue(
                dto.kassenbuchRollup.groups
                    .flatMap { it.entries }
                    .none { it.belegtext == "Tresor-Eintrag" },
                "the Tresor is not the till's cash book",
            )
        }
    }

    // -------------------------------------------------------------------------
    // Test 1 — clean month: all checks PASSED or HIDDEN
    // -------------------------------------------------------------------------

    @Test
    fun `clean month returns all checks PASSED or HIDDEN`() {
        // Use MANUAL cadence so the datev_exported check returns HIDDEN
        // (cadence=MONTHLY with no export logs would return FAILED).
        // No zone designator: the route parses these as kotlinx LocalDateTime (which rejects a
        // trailing "Z"), and the client sends LocalDateTime.toString() for exactly this reason.
        val periodStart = "2026-06-01T00:00:00"
        val periodEnd = "2026-07-01T00:00:00"

        // June chain: Z1 covers June 1–15, Z2 covers June 15–June 30 23:59:59 (perfectly chained)
        // Z2.endTime must be < periodEnd (2026-07-01T00:00:00) to be included in findClosedInWindow
        val z1Start = LocalDateTime(2026, 6, 1, 0, 0, 0)
        val z1End = LocalDateTime(2026, 6, 15, 0, 0, 0)
        val z2Start = z1End // exact chain: no gap
        val z2End = LocalDateTime(2026, 6, 30, 23, 59, 59)

        testApp(
            setupData = {
                val shop = TestData.createShopInfo()
                val manager = TestData.createManager()
                seedZBon(shop, manager, z1Start, z1End, zbonNummer = 1L)
                seedZBon(shop, manager, z2Start, z2End, zbonNummer = 2L)
            },
        ) { client ->
            val token = TestAuthHelper.loginAs(client, "manager")
            val response =
                client.get(
                    "/api/v1/monthly-close?periodStart=$periodStart&periodEnd=$periodEnd" +
                        "&comparePreviousPeriod=false&cadence=MANUAL",
                ) {
                    bearerAuth(token)
                }

            assertEquals(HttpStatusCode.OK, response.status)

            val body =
                responseJson.decodeFromString<MonthlyCloseDto.MonthlyCloseResponseDto>(
                    response.bodyAsText(),
                )

            // 2 ZBon rows in rollup
            assertEquals(2, body.zBonRollup.rows.size, "Expected 2 ZBon rows")

            // No chain gaps
            assertEquals(
                0,
                body.zBonRollup.chainGaps.size,
                "Expected no chain gaps — found: ${body.zBonRollup.chainGaps}",
            )

            // All checklist items must be PASSED or HIDDEN
            val badItems =
                body.checklist.filter {
                    it.status != MonthlyCloseDto.ChecklistStatus.PASSED &&
                        it.status != MonthlyCloseDto.ChecklistStatus.HIDDEN
                }
            assertTrue(
                badItems.isEmpty(),
                "Expected all checks PASSED or HIDDEN, but got: ${badItems.map { "${it.id}=${it.status}" }}",
            )
        }
    }

    // -------------------------------------------------------------------------
    // Test 2 — gap with bill in gap: no_bills_outside_zbon FAILED
    // -------------------------------------------------------------------------

    @Test
    fun `month with chain gap and bill in gap returns FAILED for no_bills_outside_zbon`() {
        // No zone designator: the route parses these as kotlinx LocalDateTime (which rejects a
        // trailing "Z"), and the client sends LocalDateTime.toString() for exactly this reason.
        val periodStart = "2026-06-01T00:00:00"
        val periodEnd = "2026-07-01T00:00:00"

        // Z1 covers June 1–14, Z2 covers June 15–June 30 23:59:59 → 1-day gap on June 14
        // Z2.endTime must be < periodEnd (2026-07-01T00:00:00) to be included in findClosedInWindow
        val z1Start = LocalDateTime(2026, 6, 1, 0, 0, 0)
        val z1End = LocalDateTime(2026, 6, 14, 0, 0, 0) // ends June 14
        val z2Start = LocalDateTime(2026, 6, 15, 0, 0, 0) // starts June 15 — gap!
        val z2End = LocalDateTime(2026, 6, 30, 23, 59, 59)

        // Bill closed in the gap window (June 14 00:01 – June 14 23:59)
        val billStart = LocalDateTime(2026, 6, 14, 10, 0, 0)
        val billEnd = LocalDateTime(2026, 6, 14, 10, 30, 0)

        testApp(
            setupData = {
                val shop = TestData.createShopInfo()
                val manager = TestData.createManager()
                seedZBon(shop, manager, z1Start, z1End, zbonNummer = 1L)
                seedZBon(shop, manager, z2Start, z2End, zbonNummer = 2L)

                // TableSession is required by Bill; create a minimal one
                val tableWithSession = TestData.createTableWithSession(tableNumber = "GAP1")
                seedBill(
                    employee = manager,
                    tableSession = tableWithSession.session,
                    startTime = billStart,
                    endTime = billEnd,
                    billNumber = "GAP-BILL-001",
                )
            },
        ) { client ->
            val token = TestAuthHelper.loginAs(client, "manager")
            val response =
                client.get(
                    "/api/v1/monthly-close?periodStart=$periodStart&periodEnd=$periodEnd" +
                        "&comparePreviousPeriod=false&cadence=MANUAL",
                ) {
                    bearerAuth(token)
                }

            assertEquals(HttpStatusCode.OK, response.status)

            val body =
                responseJson.decodeFromString<MonthlyCloseDto.MonthlyCloseResponseDto>(
                    response.bodyAsText(),
                )

            // Should have at least 1 chain gap containing the bill
            assertTrue(
                body.zBonRollup.chainGaps.isNotEmpty(),
                "Expected at least one chain gap",
            )
            assertTrue(
                body.zBonRollup.chainGaps.any { it.hasBillsInGap },
                "Expected at least one gap with hasBillsInGap=true",
            )

            // The no_bills_outside_zbon check must be FAILED
            val check = body.checklist.find { it.id == "no_bills_outside_zbon" }
            requireNotNull(check) { "no_bills_outside_zbon check not found in checklist" }
            assertEquals(
                MonthlyCloseDto.ChecklistStatus.FAILED,
                check.status,
                "Expected no_bills_outside_zbon to be FAILED",
            )
            assertTrue(check.affectedCount >= 1, "Expected affectedCount >= 1, got ${check.affectedCount}")
        }
    }
}
