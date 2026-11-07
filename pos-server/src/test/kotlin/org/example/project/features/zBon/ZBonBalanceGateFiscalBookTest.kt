package org.example.project.features.zBon

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.CashBook
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.setup.domain.models.ShopInfo
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
 * T9-1: `createReport`'s balance gate must read the FISCAL book's last locked entry only.
 *
 * `currentBalance` runs per cash book, and a Tresor's own Kassensturz locks the Tresor's entries.
 * The gate used to take the highest-id locked entry across ALL books, so after a Tresor count it
 * compared the Tresor's balance with the Ladenkasse's closing balance and refused every Z-Bon with
 * "Kassenbuch balance mismatch" — a state only SQL could clear.
 *
 * Mutation caught: drop the `cashBookId inList fiscalCashBookIds()` predicate and the first test goes
 * RED with a 400 naming the Tresor balance. The second test keeps the guard honest: a real Ladenkasse
 * mismatch must still be refused.
 */
@DisplayName("Z-Bon createReport — the balance gate reads the fiscal book only (T9-1)")
class ZBonBalanceGateFiscalBookTest {
    @Test
    fun `a Z-Bon is not refused after a Tresor Kassensturz locked a later Tresor entry`() =
        testAppWithBooks(fiscalLockedBalance = CLOSING_BALANCE) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.post("/api/z-bon") { bearerAuth(token) }

            assertEquals(
                HttpStatusCode.Created,
                response.status,
                "a Tresor's locked entry ($TRESOR_BALANCE) must not be compared with the Ladenkasse " +
                    "closing ($CLOSING_BALANCE): ${response.bodyAsText()}",
            )
            transaction {
                assertEquals(2, ZBon.all().count().toInt(), "the new closing must be persisted")
            }
        }

    @Test
    fun `a real Ladenkasse mismatch is still refused`() =
        testAppWithBooks(fiscalLockedBalance = BigDecimal("12.34")) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.post("/api/z-bon") { bearerAuth(token) }

            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            assertTrue(
                response.bodyAsText().contains("Kassenbuch balance mismatch"),
                "the gate must still fire on the fiscal book: ${response.bodyAsText()}",
            )
            assertTrue(
                response.bodyAsText().contains("12.34"),
                "the gate must name the fiscal book's balance, not the Tresor's: ${response.bodyAsText()}",
            )
        }

    private companion object {
        const val ADVISORY_LOCK_ALIAS =
            "CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK AS " +
                "'long pgAdvisoryXactLock(long key) { return key; }'"

        val CLOSING_BALANCE: BigDecimal = BigDecimal("3.50")
        val TRESOR_BALANCE: BigDecimal = BigDecimal("49.50")
        val Z1_END = LocalDateTime(2026, 9, 25, 21, 0)
    }

    /**
     * Seeds, in id order: a LOCKED Ladenkasse entry (balance [fiscalLockedBalance]) swept by Z1
     * (closing [CLOSING_BALANCE]); then a LOCKED Tresor entry (balance [TRESOR_BALANCE]) as a
     * Kassensturz leaves it — the highest-id locked row; then an UNLOCKED Ladenkasse entry so the new
     * closing has business to sweep.
     */
    private fun testAppWithBooks(
        fiscalLockedBalance: BigDecimal,
        block: suspend (HttpClient) -> Unit,
    ) = org.example.project.testutils.testApp(
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
                val books = CashBookRepositoryImpl()
                val ladenkasse = books.fiscalBookFor(shop)
                val tresor = books.create(shop, "Tresor", isFiscal = false, datevAccount = "1010")

                val swept = entry(shop, ladenkasse, admin, "2026-09-0001", BigDecimal("3.50"), fiscalLockedBalance, true)
                entry(shop, tresor, admin, "2026-09-0001", BigDecimal("49.50"), TRESOR_BALANCE, true)
                entry(shop, ladenkasse, admin, "2026-09-0002", BigDecimal("5.00"), fiscalLockedBalance + BigDecimal("5.00"), false)

                val z1 =
                    ZBon.new {
                        this.shopInfo = shop
                        this.employee = admin
                        this.startTime = LocalDateTime(2026, 9, 25, 8, 0)
                        this.endTime = Z1_END
                        this.cancelledAmount = BigDecimal.ZERO
                        this.totalDiscounts = BigDecimal.ZERO
                        this.totalPosRevenue = BigDecimal.ZERO
                        this.adminTip = BigDecimal.ZERO
                        this.openingBalance = BigDecimal.ZERO
                        this.expenses = BigDecimal.ZERO
                        this.cashRevenue = CLOSING_BALANCE
                        this.closingBalance = CLOSING_BALANCE
                        this.createdAt = Z1_END
                        this.trainingRevenue = BigDecimal.ZERO
                        this.trainingBillCount = 0
                        this.refundAmount = BigDecimal.ZERO
                        this.stornoBillCount = 0
                        this.zbonNummer = 1
                    }
                swept.zBon = z1
            }
        },
    ) { client -> block(client) }

    @Suppress("LongParameterList")
    private fun entry(
        shop: ShopInfo,
        book: CashBook,
        admin: Employee,
        belegNr: String,
        amount: BigDecimal,
        balance: BigDecimal,
        locked: Boolean,
    ): KassenbuchHeader =
        KassenbuchHeader.new {
            this.shop = shop
            this.cashBook = book
            this.employee = admin
            this.entryType = KassenbuchEntryType.CASH_DEPOSIT
            this.belegNr = belegNr
            this.belegDatum = "25.09.2026"
            this.belegtext = "Einlage"
            this.waehrung = "EUR"
            this.totalAmount = amount
            this.currentBalance = balance
            this.createdAt = LocalDateTime(2026, 9, 25, 12, 0)
            this.isLocked = locked
        }
}
