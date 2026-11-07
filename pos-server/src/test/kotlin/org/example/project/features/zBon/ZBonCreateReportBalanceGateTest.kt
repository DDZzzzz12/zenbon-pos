package org.example.project.features.zBon

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `createReport`'s opening balance gate must read the **last** Kassenbuch entry of the previous
 * closing — including that closing's own aggregate entry, whose `created_at` lies on or after the
 * period boundary.
 *
 * The gate used to select `createdAt less report.startTime and isLocked` ordered by id. Both halves
 * are needed for the bug: `report.startTime` IS the previous Z-Bon's `endTime`
 * (`ZBonRepositoryImpl.createReport`), and the aggregate entry that carries the closing balance is
 * written *inside* that closing — phase 1 creates the entry, then stamps `endTime`, then phase 2
 * signs it on the TSE and `V78__release_fiscal_realignment` realigns `created_at` to that
 * TSE-attested signature time. The signature time is later than `endTime`, so the entry falls
 * outside a strict `less` and the gate read the *second-to-last* entry instead. The balances then
 * disagree by exactly the last period's movement and every future closing is refused with
 * "Kassenbuch balance mismatch" — a state the operator has no way to clear, since there is no
 * endpoint that edits a locked entry or a closing balance.
 *
 * `currentBalance` is a running balance carried forward in id order, so id order is the only
 * ordering that can be trusted to find "the balance as of the last closing".
 *
 * The mutation this guard catches (rule R13): restore the `createdAt less report.startTime`
 * predicate and this test goes RED with a 400 naming both balances.
 */
@DisplayName("Z-Bon createReport — the balance gate reads the previous closing's own aggregate entry")
class ZBonCreateReportBalanceGateTest {
    @Test
    fun `a closing is not refused when the previous aggregate entry is timestamped after the period end`() =
        testAppWithRealignedAggregateEntry { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.post("/api/z-bon") { bearerAuth(token) }

            // Asserting the specific status and body: the whole defect was a deliberate 400 built on a
            // silently wrong row, so "not 500" or "a report exists" would both pass vacuously.
            assertEquals(
                HttpStatusCode.Created,
                response.status,
                "the gate must compare against the LAST locked entry ($AGGREGATE_BALANCE), " +
                    "not the one before it ($EARLIER_BALANCE): ${response.bodyAsText()}",
            )
            assertTrue(
                !response.bodyAsText().contains("Kassenbuch balance mismatch"),
                "the balance gate must not fire: ${response.bodyAsText()}",
            )

            transaction {
                assertEquals(
                    2,
                    ZBon.all().count().toInt(),
                    "the new closing must be persisted alongside the seeded one",
                )
                assertTrue(
                    KassenbuchHeader.all().all { it.isLocked },
                    "the closing must sweep the open entry it was created for",
                )
            }
        }

    // ---------------------------------------------------------------- fixtures

    private companion object {
        /** H2 stand-in for the Postgres advisory lock `createReport` takes before anything else. */
        const val ADVISORY_LOCK_ALIAS =
            "CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK AS " +
                "'long pgAdvisoryXactLock(long key) { return key; }'"

        val Z1_START = LocalDateTime(2026, 7, 30, 8, 0)
        val EARLIER_CREATED = LocalDateTime(2026, 7, 30, 18, 0)
        val Z1_END = LocalDateTime(2026, 7, 30, 21, 50, 5)

        /**
         * Load-bearing: strictly AFTER [Z1_END], which is the next period's `startTime`. This models
         * the realigned TSE signature time of the aggregate entry — the phase-2 stamp that lands
         * after phase 1 has already closed the period.
         */
        val AGGREGATE_CREATED = LocalDateTime(2026, 7, 30, 21, 50, 9)

        val EARLIER_BALANCE: BigDecimal = BigDecimal("1054.36")
        val AGGREGATE_BALANCE: BigDecimal = BigDecimal("1497.56")
    }

    /**
     * Seeds a shop one closing deep, in the exact shape the production rows had:
     *  - `earlier`: a LOCKED entry of the previous period, created before `Z1.endTime`,
     *    `currentBalance` = [EARLIER_BALANCE] — the row the buggy gate picked;
     *  - `aggregate`: `Z1`'s own LOCKED aggregate entry, created **after** `Z1.endTime`,
     *    `currentBalance` = [AGGREGATE_BALANCE] — the row that actually carries the closing balance;
     *  - `Z1` with `closingBalance` = [AGGREGATE_BALANCE], so a correct gate finds them equal;
     *  - one UNLOCKED entry so there is open business for the new closing to sweep.
     *
     * Creation order is load-bearing: `earlier` is `new {}`-ed first so `earlier.id < aggregate.id`,
     * which is what lets the id ordering and the `createdAt` window disagree.
     */
    private fun testAppWithRealignedAggregateEntry(block: suspend (io.ktor.client.HttpClient) -> Unit) =
        org.example.project.testutils.testApp(
            setupData = {
                transaction {
                    exec(ADVISORY_LOCK_ALIAS)
                    val now = currentTimeStamp()

                    Terminal.new {
                        deviceId = "unknown"
                        clientId = "TERM-TEST"
                        registeredAt = now
                        lastSeenAt = now
                    }

                    val admin =
                        Employee.find { EmployeeTable.username eq "admin" }.firstOrNull()
                            ?: TestData.createAdmin()
                    val shop = TestData.createShopInfo()

                    // (1) lower id, inside the previous period's time window.
                    val earlier =
                        KassenbuchHeader.new {
                            this.shop = shop
                            this.cashBook = CashBookRepositoryImpl().fiscalBookFor(shop)
                            this.employee = admin
                            this.entryType = KassenbuchEntryType.CASH_DEPOSIT
                            this.belegNr = "2026-07-0073"
                            this.belegDatum = "30.07.2026"
                            this.belegtext = "Lieferando"
                            this.waehrung = "EUR"
                            this.totalAmount = BigDecimal("449.00")
                            this.currentBalance = EARLIER_BALANCE
                            this.createdAt = EARLIER_CREATED
                            this.isLocked = true
                        }

                    // (2) higher id, but timestamped AFTER the period end — the realigned aggregate.
                    val aggregate =
                        KassenbuchHeader.new {
                            this.shop = shop
                            this.cashBook = CashBookRepositoryImpl().fiscalBookFor(shop)
                            this.employee = admin
                            this.entryType = KassenbuchEntryType.CASH_SALE
                            this.belegNr = "2026-07-0074"
                            this.belegDatum = "30.07.2026"
                            this.belegtext = "ZBon Nr. 1"
                            this.waehrung = "EUR"
                            this.totalAmount = BigDecimal("443.20")
                            this.currentBalance = AGGREGATE_BALANCE
                            this.createdAt = AGGREGATE_CREATED
                            this.isLocked = true
                            this.isZbonAggregate = true
                        }

                    val z1 =
                        ZBon.new {
                            this.shopInfo = shop
                            this.employee = admin
                            this.startTime = Z1_START
                            this.endTime = Z1_END
                            this.cancelledAmount = BigDecimal.ZERO
                            this.totalDiscounts = BigDecimal.ZERO
                            this.totalPosRevenue = BigDecimal.ZERO
                            this.adminTip = BigDecimal.ZERO
                            this.openingBalance = BigDecimal.ZERO
                            this.expenses = BigDecimal.ZERO
                            this.cashRevenue = AGGREGATE_BALANCE
                            this.closingBalance = AGGREGATE_BALANCE
                            this.createdAt = Z1_END
                            this.trainingRevenue = BigDecimal.ZERO
                            this.trainingBillCount = 0
                            this.refundAmount = BigDecimal.ZERO
                            this.stornoBillCount = 0
                            this.zbonNummer = 1
                        }
                    earlier.zBon = z1
                    aggregate.zBon = z1

                    // (3) the open business the new closing is taken for.
                    KassenbuchHeader.new {
                        this.shop = shop
                        this.cashBook = CashBookRepositoryImpl().fiscalBookFor(shop)
                        this.employee = admin
                        this.entryType = KassenbuchEntryType.CASH_DEPOSIT
                        this.belegNr = "2026-07-0075"
                        this.belegDatum = "31.07.2026"
                        this.belegtext = "Privateinlage"
                        this.waehrung = "EUR"
                        this.totalAmount = BigDecimal("846.10")
                        this.currentBalance = BigDecimal("2343.66")
                        this.createdAt = LocalDateTime(2026, 7, 31, 18, 0)
                    }
                }
            },
        ) { client -> block(client) }
}
