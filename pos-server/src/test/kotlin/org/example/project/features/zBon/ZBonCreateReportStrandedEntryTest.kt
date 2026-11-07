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
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round-7 D9 — a *stranded* unlocked Kassenbuch entry must be **healed** by the next closing, not
 * turned into a permanent brick.
 *
 * The closing sweep locks entries by an id window (`id greater lastLockedEntry.id`) while its
 * post-condition counts unlocked entries **globally** (`isLocked eq false`). The two scopes
 * disagree: an unlocked entry whose id sits *below* the watermark is never re-swept yet is always
 * counted, so `createReport` throws "Some kassenbuch entries are not locked" on **every** future
 * closing — permanently, with no repair path (there is exactly one `isLocked = true` writer, KB has
 * no delete/unlock endpoint). The same stranded row also keeps `OpenBusinessCheck` true forever, so
 * every fiscal `updateShopInfo` 409s. The whole shop is wedged.
 *
 * Such a row is produced by the createSignedEntry-vs-createReport `REPEATABLE_READ` race (an entry
 * committed after the closing pinned its snapshot has a low id but is invisible to that closing).
 * This test reproduces the resulting **state** directly (deterministic, no race needed): a prior
 * closing `Z1` with a locked anchor entry of a *higher* id, and an unlocked entry of a *lower* id
 * that no closing ever swept.
 *
 * The mutation this guard catches (rule R13): revert the sweep predicate to `id greater watermark`
 * and this test goes RED — `createReport` answers 400 and the stranded entry stays unlocked.
 */
@DisplayName("Z-Bon createReport — a stranded unlocked Kassenbuch entry is healed, not a permanent brick (R7-D9)")
class ZBonCreateReportStrandedEntryTest {
    @Test
    fun `a stranded unlocked entry below the watermark is swept and locked by the next closing`() =
        testAppWithStrandedEntry { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.post("/api/z-bon") { bearerAuth(token) }

            assertEquals(
                HttpStatusCode.Created,
                response.status,
                "a closing must HEAL a stranded unlocked entry, not answer 400 forever: " +
                    response.bodyAsText(),
            )

            transaction {
                assertTrue(
                    KassenbuchHeader.all().all { it.isLocked },
                    "the closing must leave no unlocked entry behind — the stranded row included",
                )
                val stranded =
                    KassenbuchHeader
                        .find { KassenbuchHeaderTable.belegtext eq STRANDED_TEXT }
                        .first()
                assertTrue(stranded.isLocked, "the previously-stranded entry is now locked")
                assertNotNull(
                    stranded.zBon,
                    "the previously-stranded entry is assigned to the closing that healed it",
                )
            }
        }

    // ---------------------------------------------------------------- fixtures

    private companion object {
        const val STRANDED_TEXT = "STRANDED-PRIVATEINLAGE"

        /** H2 stand-in for the Postgres advisory lock `createReport` takes before anything else. */
        const val ADVISORY_LOCK_ALIAS =
            "CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK AS " +
                "'long pgAdvisoryXactLock(long key) { return key; }'"

        val Z1_START = LocalDateTime(2026, 7, 18, 8, 0)
        val ANCHOR_CREATED = LocalDateTime(2026, 7, 18, 9, 0)
        val Z1_END = LocalDateTime(2026, 7, 18, 20, 0)
    }

    /**
     * Seeds the exact post-race state:
     *  - a prior closing `Z1` (zbonNummer 1, closingBalance 100.00),
     *  - `anchor`: a LOCKED entry assigned to `Z1`, created before `Z1.endTime` — it is the
     *    watermark the next closing computes (`lastKassenEntry` = highest-id locked entry before
     *    the period start = `Z1.endTime`),
     *  - `stranded`: an UNLOCKED entry created FIRST so its id is *below* `anchor`'s, with `zBon`
     *    null — the entry no closing ever swept.
     *
     * Creation order is load-bearing: `stranded` is `new {}`-ed before `anchor`, so
     * `stranded.id < anchor.id < watermark`, which is precisely what makes the id-bounded sweep
     * skip it while the global post-condition counts it.
     */
    private fun testAppWithStrandedEntry(block: suspend (io.ktor.client.HttpClient) -> Unit) =
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

                    // (1) stranded FIRST => lowest id, unlocked, never in a closing.
                    KassenbuchHeader.new {
                        this.shop = shop
                        this.cashBook = CashBookRepositoryImpl().fiscalBookFor(shop)
                        this.employee = admin
                        this.entryType = KassenbuchEntryType.CASH_DEPOSIT
                        this.belegNr = "2026-07-0002"
                        this.belegDatum = "19.07.2026"
                        this.belegtext = STRANDED_TEXT
                        this.waehrung = "EUR"
                        this.totalAmount = BigDecimal("50.00")
                        this.currentBalance = BigDecimal("150.00")
                        this.createdAt = now
                    }

                    // (2) anchor SECOND => higher id, locked, belongs to Z1, before Z1.endTime.
                    val anchor =
                        KassenbuchHeader.new {
                            this.shop = shop
                            this.cashBook = CashBookRepositoryImpl().fiscalBookFor(shop)
                            this.employee = admin
                            this.entryType = KassenbuchEntryType.CASH_DEPOSIT
                            this.belegNr = "2026-07-0001"
                            this.belegDatum = "18.07.2026"
                            this.belegtext = "ANCHOR"
                            this.waehrung = "EUR"
                            this.totalAmount = BigDecimal("100.00")
                            this.currentBalance = BigDecimal("100.00")
                            this.createdAt = ANCHOR_CREATED
                            this.isLocked = true
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
                            this.cashRevenue = BigDecimal("100.00")
                            this.closingBalance = BigDecimal("100.00")
                            this.createdAt = Z1_END
                            this.trainingRevenue = BigDecimal.ZERO
                            this.trainingBillCount = 0
                            this.refundAmount = BigDecimal.ZERO
                            this.stornoBillCount = 0
                            this.zbonNummer = 1
                        }
                    anchor.zBon = z1
                }
            },
        ) { client -> block(client) }
}
