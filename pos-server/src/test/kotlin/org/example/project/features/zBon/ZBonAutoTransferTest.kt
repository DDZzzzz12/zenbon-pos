package org.example.project.features.zBon

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.setup.database.tables.ShopConfigurationTable
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 2 of the Z-Bon auto-transfer plan (2026-08-08).
 *
 * A `CASH_TRANSFER_OUT` (Geldtransit) totalAmount is negative, so the naive sign-based split in
 * `ZBonServiceImpl.createReport` used to fold it into `expenses`. Under DSFinV-K a Geldtransit is
 * its own GV_TYP, distinct from `Auszahlung` — it must not be reported as an expense.
 *
 * The subtlety this test exists to pin down: `closingBalance` must NOT change. The cash genuinely
 * left the drawer (to the Tresor), so the balance must still fall by the transfer amount — only the
 * *reported* `expenses` figure excludes it. A "fix" that drops the transfer from both `expenses` and
 * `closingBalance` would make the Z-Bon claim the money never left, which is worse than the bug.
 */
@DisplayName("Z-Bon closing separates a Geldtransit from expenses")
class ZBonAutoTransferTest {
    private companion object {
        const val ADVISORY_LOCK_ALIAS =
            "CREATE ALIAS IF NOT EXISTS PG_ADVISORY_XACT_LOCK AS " +
                "'long pgAdvisoryXactLock(long key) { return key; }'"

        /** The Tresor's starting balance in the Task 4 fixtures — proves "unchanged" means something. */
        val TRESOR_OPENING_BALANCE: BigDecimal = BigDecimal("300.00")
    }

    @Test
    fun `a Geldtransit does not count as an expense but does reduce the closing balance`() =
        testAppWithFiscalGeldtransit { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.post("/api/z-bon") { bearerAuth(token) }

            assertEquals(
                HttpStatusCode.Created,
                response.status,
                "expected the closing to succeed: ${response.bodyAsText()}",
            )

            transaction {
                val report = ZBon.all().first()

                // NOT -230.00 — if the transfer were still folded into expenses (the pre-fix
                // behaviour), this would be -30.00 + -200.00 = -230.00.
                assertEquals(
                    0,
                    BigDecimal("-30.00").compareTo(report.expenses),
                    "the Geldtransit must not be counted as an expense, got ${report.expenses}",
                )
                assertEquals(
                    0,
                    BigDecimal("500.00").compareTo(report.cashRevenue),
                    "cashRevenue must only hold the genuine deposit, got ${report.cashRevenue}",
                )
                // The important half: opening(0) + revenues(500) + expenses(-30) + transfer(-200) =
                // 270.00. A fix that merely excludes the transfer from expenses without still
                // applying it here would leave this at 470.00 — claiming the cash never left.
                assertEquals(
                    0,
                    BigDecimal("270.00").compareTo(report.closingBalance),
                    "closingBalance must still fall by the transfer amount — the cash really left " +
                        "the drawer, got ${report.closingBalance}",
                )
            }
        }

    /**
     * Task 4 of the same plan.
     *
     * A Tresor exists, but `CASH_BOOK_TRANSFER_TARGET` is never set in this fixture — the feature
     * must stay inert, exactly as it behaved before Task 4 landed.
     */
    @Test
    fun `no target configured means no transfer — today's behaviour is unchanged`() =
        testAppWithTransferScenario(revenue = BigDecimal("400.00"), configureTransferTarget = false) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.post("/api/z-bon") { bearerAuth(token) }

            assertEquals(
                HttpStatusCode.Created,
                response.status,
                "expected the closing to succeed: ${response.bodyAsText()}",
            )

            transaction {
                assertTrue(
                    KassenbuchHeader.all().none { it.transferId != null },
                    "no header may carry a transferId when no transfer target is configured",
                )

                val tresorEntries = KassenbuchHeader.all().filter { it.cashBook.name == "Tresor" }
                assertEquals(
                    1,
                    tresorEntries.size,
                    "no leg should land in the Tresor when the auto-transfer feature is off",
                )
                assertEquals(
                    0,
                    TRESOR_OPENING_BALANCE.compareTo(tresorEntries.single().currentBalance),
                    "the Tresor balance must be unchanged when no transfer runs",
                )
            }
        }

    /**
     * The ordering guard this whole task exists for: the Ladenkasse leg must be locked by THIS
     * closing's own sweep (created before it runs), and the Tresor leg must stay unlocked (a Tresor
     * is swept by its own Kassensturz, never by a register closing).
     */
    @Test
    fun `with a target configured the closing books both legs`() =
        testAppWithTransferScenario(revenue = BigDecimal("800.00"), configureTransferTarget = true) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.post("/api/z-bon") { bearerAuth(token) }

            assertEquals(
                HttpStatusCode.Created,
                response.status,
                "expected the closing to succeed: ${response.bodyAsText()}",
            )

            transaction {
                val transferLegs = KassenbuchHeader.all().filter { it.transferId != null }
                assertEquals(2, transferLegs.size, "a transfer books exactly two legs")
                assertEquals(
                    1,
                    transferLegs.map { it.transferId }.distinct().size,
                    "both legs must share ONE transferId",
                )

                val outLeg = transferLegs.single { it.entryType == KassenbuchEntryType.CASH_TRANSFER_OUT }
                val inLeg = transferLegs.single { it.entryType == KassenbuchEntryType.CASH_TRANSFER_IN }

                assertTrue(outLeg.cashBook.isFiscal, "the OUT leg must sit on the fiscal Ladenkasse")
                assertEquals(
                    0,
                    BigDecimal("-800.00").compareTo(outLeg.totalAmount),
                    "the Ladenkasse leg must be negative, got ${outLeg.totalAmount}",
                )
                // The assertion that matters: THIS closing's sweep must have locked it. A leg
                // created after the sweep would be stranded is_locked=false forever, with no
                // unlock/delete/repair path (see the ordering comment in ZBonServiceImpl.createReport).
                assertTrue(
                    outLeg.isLocked,
                    "the fiscal leg must be locked by THIS closing's sweep",
                )

                assertFalse(inLeg.cashBook.isFiscal, "the IN leg must sit on the non-fiscal Tresor")
                assertEquals(
                    0,
                    BigDecimal("800.00").compareTo(inLeg.totalAmount),
                    "the Tresor leg must be positive, got ${inLeg.totalAmount}",
                )
                assertEquals("Tageseinnahmen", inLeg.belegtext)
                assertEquals(TseSigningStatus.NOT_APPLICABLE, inLeg.tseSigningStatus)
                // The other half of the assertion that matters: a Tresor entry is never swept by a
                // register closing — it stays open for its own Kassensturz.
                assertFalse(inLeg.isLocked, "a Tresor entry must never be locked by a register closing")

                val outDocId = outLeg.linkedDocumentId
                val inDocId = inLeg.linkedDocumentId
                assertNotNull(outDocId, "the Ladenkasse leg must carry a linked document")
                assertNotNull(inDocId, "the Tresor leg must carry a linked document")
                assertNotEquals(
                    outDocId,
                    inDocId,
                    "linkedDocumentId is uniqueIndex — the two legs cannot share one document",
                )
            }
        }

    /**
     * A distinct (non-round) revenue figure from the other two tests, so this cannot pass by
     * coincidentally matching a hardcoded literal shared with them.
     */
    @Test
    fun `the transfer amount is the Z-Bon's cash revenue`() =
        testAppWithTransferScenario(revenue = BigDecimal("356.78"), configureTransferTarget = true) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.post("/api/z-bon") { bearerAuth(token) }

            assertEquals(
                HttpStatusCode.Created,
                response.status,
                "expected the closing to succeed: ${response.bodyAsText()}",
            )

            transaction {
                val report = ZBon.all().first()
                assertEquals(
                    0,
                    BigDecimal("356.78").compareTo(report.cashRevenue),
                    "fixture guard: report.cashRevenue must reflect the seeded takings, got ${report.cashRevenue}",
                )

                val outLeg = KassenbuchHeader.all().single { it.entryType == KassenbuchEntryType.CASH_TRANSFER_OUT }
                val inLeg = KassenbuchHeader.all().single { it.entryType == KassenbuchEntryType.CASH_TRANSFER_IN }

                assertEquals(
                    0,
                    report.cashRevenue.compareTo(outLeg.totalAmount.negate()),
                    "the Ladenkasse leg's magnitude must equal report.cashRevenue exactly",
                )
                assertEquals(
                    0,
                    report.cashRevenue.compareTo(inLeg.totalAmount),
                    "the Tresor leg's amount must equal report.cashRevenue exactly",
                )
            }
        }

    /**
     * The fiscal leg of the closing's Geldtransit is a Ladenkasse entry like any other, so the
     * closing must sign it — or queue it — the way `createTransfer` signs a manual Geldtransit.
     * `createTransferLegsInTransaction` marks the leg PENDING and leaves signing to its caller; the
     * closing never did it, so the leg sat PENDING with no TSE transaction and no queue row, and
     * `TsePendingSweeper` (bills only) never rescued it. Found on the 2026-09-11 upgrade rehearsal.
     *
     * The closing's own CASH_SALE aggregate is not signed at all since spec 2026-10-09 (it restates
     * sales each bill already signed): it must record NOT_APPLICABLE and carry no TSE transaction.
     */
    @Test
    fun `the closing signs its Ladenkasse leg and leaves its aggregate unsigned`() =
        testAppWithTransferScenario(revenue = BigDecimal("640.00"), configureTransferTarget = true) { client ->
            val token = TestAuthHelper.loginAsAdmin(client)
            val response = client.post("/api/z-bon") { bearerAuth(token) }

            assertEquals(
                HttpStatusCode.Created,
                response.status,
                "expected the closing to succeed: ${response.bodyAsText()}",
            )

            transaction {
                val aggregate = KassenbuchHeader.all().single { it.isZbonAggregate }
                val outLeg = KassenbuchHeader.all().single { it.entryType == KassenbuchEntryType.CASH_TRANSFER_OUT }

                assertEquals(
                    TseSigningStatus.NOT_APPLICABLE,
                    aggregate.tseSigningStatus,
                    "the aggregate restates already-signed sales: no TSE transaction (spec 2026-10-09)",
                )
                assertNull(aggregate.tseTransaction, "the aggregate carries no TSE transaction")
                assertNotEquals(
                    TseSigningStatus.PENDING,
                    outLeg.tseSigningStatus,
                    "the Ladenkasse leg must be signed or queued by the closing, not stranded PENDING",
                )
            }
        }

    // ---------------------------------------------------------------- fixture

    private fun testAppWithFiscalGeldtransit(block: suspend (io.ktor.client.HttpClient) -> Unit) =
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
                    val shop = if (ShopInfo.all().empty()) TestData.createShopInfo() else ShopInfo.all().first()

                    val books = CashBookRepositoryImpl()
                    val kasse = books.fiscalBookFor(shop)
                    val tresor = books.create(shop, "Tresor", isFiscal = false, datevAccount = "1010")

                    // A genuine positive: a deposit into the till.
                    KassenbuchHeader.new {
                        this.shop = shop
                        this.employee = admin
                        this.cashBook = kasse
                        this.entryType = KassenbuchEntryType.CASH_DEPOSIT
                        this.belegNr = "2026-07-0001"
                        this.belegDatum = "19.07.2026"
                        this.belegtext = "Einlage Ladenkasse"
                        this.waehrung = "EUR"
                        this.totalAmount = BigDecimal("500.00")
                        this.currentBalance = BigDecimal("500.00")
                        this.createdAt = now
                    }
                    // A genuine expense — this one MUST still land in `expenses`.
                    KassenbuchHeader.new {
                        this.shop = shop
                        this.employee = admin
                        this.cashBook = kasse
                        this.entryType = KassenbuchEntryType.CASH_EXPENSE
                        this.belegNr = "2026-07-0002"
                        this.belegDatum = "19.07.2026"
                        this.belegtext = "Buerobedarf"
                        this.waehrung = "EUR"
                        this.totalAmount = BigDecimal("-30.00")
                        this.currentBalance = BigDecimal("470.00")
                        this.createdAt = now
                    }
                    // The Geldtransit under test: booked on the FISCAL book (the sweep only
                    // collects fiscal entries), moving cash out to the Tresor.
                    KassenbuchHeader.new {
                        this.shop = shop
                        this.employee = admin
                        this.cashBook = kasse
                        this.counterpartyBook = tresor
                        this.entryType = KassenbuchEntryType.CASH_TRANSFER_OUT
                        this.belegNr = "2026-07-0003"
                        this.belegDatum = "19.07.2026"
                        this.belegtext = "Geldtransit Tresor"
                        this.waehrung = "EUR"
                        this.totalAmount = BigDecimal("-200.00")
                        this.currentBalance = BigDecimal("270.00")
                        this.createdAt = now
                    }
                }
            },
        ) { client -> block(client) }

    /**
     * A fiscal Ladenkasse with [revenue] of unswept takings, and a Tresor with
     * [TRESOR_OPENING_BALANCE] already in it (so a "the Tresor balance is unchanged" assertion
     * proves something). When [configureTransferTarget] is true, `CASH_BOOK_TRANSFER_TARGET` is set
     * to the Tresor's id.
     *
     * `ShopConfigurationManager.refreshConfiguration()` runs unconditionally, even when
     * [configureTransferTarget] is false: it is a process-wide singleton with a 30-minute cache, so
     * without forcing a reload a prior test's cached settings (in either state) could leak into this
     * one depending on JUnit's execution order.
     */
    private fun testAppWithTransferScenario(
        revenue: BigDecimal,
        configureTransferTarget: Boolean,
        block: suspend (io.ktor.client.HttpClient) -> Unit,
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
                val shop = if (ShopInfo.all().empty()) TestData.createShopInfo() else ShopInfo.all().first()

                val books = CashBookRepositoryImpl()
                val kasse = books.fiscalBookFor(shop)
                val tresor = books.create(shop, "Tresor", isFiscal = false, datevAccount = "1010")

                // The Tresor's own opening balance — untouched by the closing in every scenario
                // except when a transfer lands the Tageseinnahmen leg on top of it.
                KassenbuchHeader.new {
                    this.shop = shop
                    this.employee = admin
                    this.cashBook = tresor
                    this.entryType = KassenbuchEntryType.CASH_DEPOSIT
                    this.belegNr = "2026-07-8001"
                    this.belegDatum = "19.07.2026"
                    this.belegtext = "Tresor Anfangsbestand"
                    this.waehrung = "EUR"
                    this.totalAmount = TRESOR_OPENING_BALANCE
                    this.currentBalance = TRESOR_OPENING_BALANCE
                    this.createdAt = now
                }

                // The day's takings on the Ladenkasse — unswept, so the closing's own aggregate +
                // this row are the only contributors to report.cashRevenue.
                KassenbuchHeader.new {
                    this.shop = shop
                    this.employee = admin
                    this.cashBook = kasse
                    this.entryType = KassenbuchEntryType.CASH_DEPOSIT
                    this.belegNr = "2026-07-8002"
                    this.belegDatum = "19.07.2026"
                    this.belegtext = "Tageseinnahmen"
                    this.waehrung = "EUR"
                    this.totalAmount = revenue
                    this.currentBalance = revenue
                    this.createdAt = now
                }

                if (configureTransferTarget) {
                    ShopConfigurationTable.upsert {
                        it[ShopConfigurationTable.shopId] = shop.id.value
                        it[ShopConfigurationTable.key] = ConfigKey.CASH_BOOK_TRANSFER_TARGET.key
                        it[ShopConfigurationTable.value] = JsonPrimitive(tresor.id.value.toString())
                        it[ShopConfigurationTable.description] = "test"
                        it[ShopConfigurationTable.updatedAt] = null
                    }
                }
            }
            runBlocking { ShopConfigurationManager.refreshConfiguration() }
        },
    ) { client -> block(client) }
}
