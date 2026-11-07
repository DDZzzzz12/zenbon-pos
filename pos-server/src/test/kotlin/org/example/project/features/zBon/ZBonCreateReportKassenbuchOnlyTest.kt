package org.example.project.features.zBon

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.zBon.domain.CashScope
import org.example.project.features.zBon.domain.OpenBusinessCheck
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Round-7 C9, the **other** polarity of [org.example.project.features.zBon.domain.OpenBusinessCheck].
 *
 * Widening the predicate to cover Kassenbuch entries is only half a fix. `createReport` gated twice
 * on bills: once through `OpenBusinessCheck` and again on `allBills.isEmpty()`. Widening only the
 * first would **deadlock the operator**: a booked Privateinlage blocks the fiscal shop edit
 * (Tz. 3.2 closing duty) while the closing that would clear it is refused for "no bills".
 *
 * So a Kassenabschluss over a day with Kassenbuch movement but no sales must succeed, and both
 * gates must agree on what "nothing to close" means.
 *
 * The deadlock has a **second level**, which the first version of this fix moved rather than
 * removed and a reviewer caught: `ZBonRepositoryImpl.createReport` derives the period start from
 * the first `TableSession` when no closing exists yet. `BillTable.tableSession` is a non-nullable
 * reference, so before the widening "open business" implied a bill implied a session and that
 * throw was structurally unreachable. Kassenbuch has no `TableSession` coupling at all, so a
 * Kassenbuch-only shop reached it — and it was a bare `Exception`, i.e. a 500 on the very endpoint
 * the 409 tells the operator to call. Hence `a Kassenbuch-only shop with NO TableSession …` below.
 */
@DisplayName("Z-Bon createReport — a Kassenbuch-only day is closable (C9)")
class ZBonCreateReportKassenbuchOnlyTest {
    @Test
    fun `a day with a Kassenbuch entry but no bills can be closed`() =
        testAppWithKbOnlyDay { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.post("/api/z-bon") { bearerAuth(token) }

            assertEquals(
                HttpStatusCode.Created,
                response.status,
                "a booked Kassenbuch Vorgang is business that must be closable: ${response.bodyAsText()}",
            )

            transaction {
                val report = ZBon.all().first()
                assertTrue(
                    KassenbuchHeader.all().all { it.isLocked },
                    "the closing must sweep and lock the Kassenbuch entry it was created for",
                )
                assertEquals(
                    report.id.value,
                    KassenbuchHeader
                        .all()
                        .first { !it.isZbonAggregate }
                        .zBon
                        ?.id
                        ?.value,
                    "the entry must be assigned to the closing that swept it",
                )
            }
        }

    /**
     * The deadlock's second level. A shop that has only ever booked Kassenbuch movement — no
     * table, no session, no bill — must still be able to take the Kassenabschluss that its own
     * 409 `SHOP_INFO_CLOSING_REQUIRED` demands. Pre-fix this hit a bare `Exception("No session
     * found")`, which `StatusPages` maps through its `Throwable` handler to a 500 with no
     * actionable message, leaving the operator with no way out short of ringing up a real sale.
     */
    @Test
    fun `a Kassenbuch-only shop with NO TableSession can still close and then edit its Stammdaten`() =
        testAppWithKbOnlyDayAndNoSession { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.post("/api/z-bon") { bearerAuth(token) }

            assertTrue(
                response.status.value < 500,
                "the endpoint the 409 points the operator to must never answer 5xx: " +
                    "${response.status} ${response.bodyAsText()}",
            )
            assertEquals(
                HttpStatusCode.Created,
                response.status,
                "a Kassenbuch-only shop has business to close: ${response.bodyAsText()}",
            )

            // The whole point of clearing it: the Tz. 3.2 closing duty must now be satisfied.
            transaction {
                assertTrue(
                    KassenbuchHeader.all().all { it.isLocked },
                    "the closing must sweep the entry that was blocking the shop edit",
                )
                assertFalse(
                    OpenBusinessCheck.hasOpenBusiness(CashScope.ALL_BOOKS),
                    "after the closing the fiscal shop edit must be possible again",
                )
            }
        }

    @Test
    fun `a day with nothing open at all is still refused`() =
        testAppWithEmptyDay { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.post("/api/z-bon") { bearerAuth(token) }

            // Asserting the SPECIFIC status on purpose: `!= Created` plus a zero row count both pass
            // vacuously on a 500, because zbonNummer is computed in-transaction and a crash rolls the
            // row back — exactly how the no-session 500 above hid in this suite.
            assertEquals(
                HttpStatusCode.BadRequest,
                response.status,
                "an empty day must be refused deliberately, not by crashing: ${response.bodyAsText()}",
            )
            assertTrue(
                response.bodyAsText().contains("ZBON_NOTHING_TO_CLOSE"),
                "the refusal must be the OpenBusinessCheck gate, with its own code: ${response.bodyAsText()}",
            )
            assertEquals(0, transaction { ZBon.all().count() })
        }

    // ---------------------------------------------------------------- fixtures

    private companion object {
        /** H2 stand-in for the Postgres advisory lock `createReport` takes before anything else. */
        const val ADVISORY_LOCK_ALIAS =
            "CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK AS " +
                "'long pgAdvisoryXactLock(long key) { return key; }'"
    }

    private fun testAppWithKbOnlyDay(block: suspend (io.ktor.client.HttpClient) -> Unit) =
        org.example.project.testutils.testApp(
            setupData = {
                // A shop that has traded before: `repository.createReport` derives the period start
                // from the first TableSession when no previous closing exists. Closed and freed, so
                // it is not itself open business.
                val tws = TestData.createTableWithSession("KB1")
                transaction {
                    // H2 stand-in for the Postgres advisory lock used to serialize Z-Bon creation.
                    exec(ADVISORY_LOCK_ALIAS)
                    val now = currentTimeStamp()

                    // Terminal for the token's deviceId (no X-Device-ID header at login => "unknown")
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

                    val admin =
                        Employee.find { EmployeeTable.username eq "admin" }.firstOrNull()
                            ?: TestData.createAdmin()
                    val shop = TestData.createShopInfo()
                    KassenbuchHeader.new {
                        this.shop = shop
                        this.cashBook = CashBookRepositoryImpl().fiscalBookFor(shop)
                        this.employee = admin
                        this.entryType = KassenbuchEntryType.CASH_DEPOSIT
                        this.belegNr = "2026-07-0001"
                        this.belegDatum = "19.07.2026"
                        this.belegtext = "Privateinlage"
                        this.waehrung = "EUR"
                        this.totalAmount = BigDecimal("100.00")
                        this.currentBalance = BigDecimal("100.00")
                        this.createdAt = currentTimeStamp()
                    }
                }
            },
        ) { client -> block(client) }

    /**
     * A shop that has NEVER opened a table: zero `TableSession` rows, one unlocked Kassenbuch
     * entry. Deliberately does not call `createTableWithSession` — seeding one would recreate the
     * coupling this test exists to prove is gone.
     */
    private fun testAppWithKbOnlyDayAndNoSession(block: suspend (io.ktor.client.HttpClient) -> Unit) =
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
                    KassenbuchHeader.new {
                        this.shop = shop
                        this.cashBook = CashBookRepositoryImpl().fiscalBookFor(shop)
                        this.employee = admin
                        this.entryType = KassenbuchEntryType.CASH_DEPOSIT
                        this.belegNr = "2026-07-0001"
                        this.belegDatum = "19.07.2026"
                        this.belegtext = "Privateinlage"
                        this.waehrung = "EUR"
                        this.totalAmount = BigDecimal("100.00")
                        this.currentBalance = BigDecimal("100.00")
                        this.createdAt = now
                    }
                }
            },
        ) { client -> block(client) }

    private fun testAppWithEmptyDay(block: suspend (io.ktor.client.HttpClient) -> Unit) =
        org.example.project.testutils.testApp(
            setupData = {
                transaction {
                    exec(ADVISORY_LOCK_ALIAS)
                    Employee.find { EmployeeTable.username eq "admin" }.firstOrNull()
                        ?: TestData.createAdmin()
                    if (ShopInfo.all().empty()) TestData.createShopInfo()
                }
            },
        ) { client -> block(client) }
}
