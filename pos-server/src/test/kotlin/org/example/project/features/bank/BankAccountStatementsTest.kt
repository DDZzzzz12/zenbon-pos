package org.example.project.features.bank

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.features.bank.data.repository.BankAccountRepositoryImpl
import org.example.project.features.bank.data.service.BankAccountServiceImpl
import org.example.project.features.bank.data.service.StatementAccountResolver
import org.example.project.features.bank.domain.models.BankAccount
import org.example.project.features.document.domain.models.BankStatement
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `GET /api/bank-accounts/{id}/statements` — the statements one account holds.
 *
 * This is the only place a per-account statement history exists. The list endpoint reports gaps as
 * `continuityBreaks`, a list of bare statement ids that a client can only render as database keys;
 * here each statement carries its own `continuityBreak` flag beside the period it covers, which is
 * what a continuity strip can actually be drawn from.
 */
class BankAccountStatementsTest {
    private val repository = BankAccountRepositoryImpl()
    private val service = BankAccountServiceImpl(repository, StatementAccountResolver(repository))

    private fun account(iban: String): BankAccount =
        repository.create(
            shop = ShopInfo.all().first(),
            iban = iban,
            bic = null,
            bankName = "Commerzbank",
        )

    private fun statement(
        account: BankAccount?,
        month: Int,
        opening: String?,
        closing: String?,
        number: String? = null,
    ): BankStatement =
        BankStatement.new {
            this.bankAccount = account
            this.statementNumber = number
            this.statementDate = LocalDateTime(2027, month, 28, 0, 0)
            this.periodStart = LocalDateTime(2027, month, 1, 0, 0)
            this.periodEnd = LocalDateTime(2027, month, 28, 0, 0)
            this.openingBalance = opening?.let { BigDecimal(it) }
            this.closingBalance = closing?.let { BigDecimal(it) }
            this.createdAt = LocalDateTime(2027, month, 28, 0, 0)
        }

    @Test
    fun `statements come back oldest first with their period and balances`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val ibanOf =
                transaction {
                    val acc = account("DE89370400440532013000")
                    statement(acc, month = 2, opening = "100.0000", closing = "250.0000", number = "2027/02")
                    statement(acc, month = 1, opening = "0.0000", closing = "100.0000", number = "2027/01")
                    acc.id.value to acc.iban
                }

            val response = runBlocking { service.statementsFor(ibanOf.first) }

            assertEquals(ibanOf.second, response.iban)
            assertEquals(listOf("2027/01", "2027/02"), response.items.map { it.statementNumber })
            assertEquals(listOf("0.00", "100.00"), response.items.map { it.openingBalance })
            assertEquals(listOf("100.00", "250.00"), response.items.map { it.closingBalance })
            assertEquals("2027-01-01T00:00", response.items.first().periodStart)
        }
    }

    /** The whole point: the gap is on the statement that fails to continue, not in a separate list. */
    @Test
    fun `a statement whose opening balance does not continue the previous one is flagged`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val accountId =
                transaction {
                    val acc = account("DE89370400440532013000")
                    statement(acc, month = 1, opening = "0.0000", closing = "100.0000")
                    // March opens at 400 — February is missing.
                    statement(acc, month = 3, opening = "400.0000", closing = "450.0000")
                    acc.id.value
                }

            val items = runBlocking { service.statementsFor(accountId) }.items

            assertFalse(items[0].continuityBreak, "the first statement continues nothing and is never a break")
            assertTrue(items[1].continuityBreak)
        }
    }

    /** T5-9: the overlap the import recorded reaches the statement list, not only its bookings. */
    @Test
    fun `a statement that overlaps an earlier import carries the earlier statement's id`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val (accountId, januaryId) =
                transaction {
                    val acc = account("DE89370400440532013000")
                    val january = statement(acc, month = 1, opening = "0.0000", closing = "100.0000")
                    val reExport = statement(acc, month = 1, opening = "0.0000", closing = "100.0000")
                    reExport.overlappingStatement = january
                    acc.id.value to january.id.value
                }

            val items = runBlocking { service.statementsFor(accountId) }.items

            assertEquals(listOf(null, januaryId), items.map { it.overlappingStatementId })
        }
    }

    /**
     * E2E 18.6: February, then March, then a byte-different re-export of March. One statement is
     * missing (Feb closes 2394.16, March opens 3200.00) — but the re-export, which repeats March,
     * was counted as a second "Auszug fehlt" because its opening does not continue March's closing.
     * Nothing can be missing between two statements that cover the same period.
     */
    @Test
    fun `an overlapping re-import adds no second gap`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val (accountId, marchId) =
                transaction {
                    val acc = account("DE89370400440532013000")
                    statement(acc, month = 2, opening = "1234.5600", closing = "2394.1600")
                    val march = statement(acc, month = 3, opening = "3200.0000", closing = "3550.0100")
                    val reExport = statement(acc, month = 3, opening = "3200.0000", closing = "3550.0100")
                    reExport.overlappingStatement = march
                    acc.id.value to march.id.value
                }

            val items = runBlocking { service.statementsFor(accountId) }.items
            assertEquals(listOf(false, true, false), items.map { it.continuityBreak })

            val listed = runBlocking { service.list() }.items.single { it.id == accountId }
            assertEquals(listOf(marchId), listed.continuityBreaks, "one missing statement is one gap")
        }
    }

    /**
     * The same overlap in the other order: a quarter export (Jan–Mar) imported after March sorts
     * BEFORE March, because its period starts earlier. March then repeats the tail of the quarter
     * rather than continuing it, and is no more a gap than the re-export above.
     */
    @Test
    fun `a longer re-export sorting before the statement it overlaps adds no gap either`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val accountId =
                transaction {
                    val acc = account("DE89370400440532013000")
                    val march = statement(acc, month = 3, opening = "300.0000", closing = "450.0000")
                    val quarter = statement(acc, month = 1, opening = "0.0000", closing = "450.0000")
                    quarter.overlappingStatement = march
                    statement(acc, month = 4, opening = "450.0000", closing = "500.0000")
                    acc.id.value
                }

            val items = runBlocking { service.statementsFor(accountId) }.items
            assertTrue(items.none { it.continuityBreak }, "breaks: ${items.map { it.continuityBreak }}")
        }
    }

    /** The overlap rule must not swallow a real gap AFTER a duplicate. */
    @Test
    fun `a real gap after an overlapping re-import is still reported`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val (accountId, aprilId) =
                transaction {
                    val acc = account("DE89370400440532013000")
                    val january = statement(acc, month = 1, opening = "0.0000", closing = "100.0000")
                    val reExport = statement(acc, month = 1, opening = "0.0000", closing = "100.0000")
                    reExport.overlappingStatement = january
                    // February and March are missing.
                    val april = statement(acc, month = 4, opening = "700.0000", closing = "800.0000")
                    acc.id.value to april.id.value
                }

            val listed = runBlocking { service.list() }.items.single { it.id == accountId }
            assertEquals(listOf(aprilId), listed.continuityBreaks)
        }
    }

    @Test
    fun `an unbroken run has no breaks`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val accountId =
                transaction {
                    val acc = account("DE89370400440532013000")
                    statement(acc, month = 1, opening = "0.0000", closing = "100.0000")
                    // Scale differs on purpose: 100.00 must continue 100.0000, since the column is
                    // DECIMAL(19,4) and a parser may hand us two decimals.
                    statement(acc, month = 2, opening = "100.00", closing = "250.0000")
                    acc.id.value
                }

            assertTrue(runBlocking { service.statementsFor(accountId) }.items.none { it.continuityBreak })
        }
    }

    /** "Cannot tell" must not read as "all fine" in a check whose whole purpose is completeness. */
    @Test
    fun `a missing balance counts as a break`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val accountId =
                transaction {
                    val acc = account("DE89370400440532013000")
                    statement(acc, month = 1, opening = "0.0000", closing = null)
                    statement(acc, month = 2, opening = "100.0000", closing = "250.0000")
                    acc.id.value
                }

            assertTrue(runBlocking { service.statementsFor(accountId) }.items[1].continuityBreak)
        }
    }

    @Test
    fun `another account's statements are not included`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val mine =
                transaction {
                    val a = account("DE89370400440532013000")
                    val b = account("DE02120300000000202051")
                    statement(a, month = 1, opening = "0.0000", closing = "100.0000", number = "meins")
                    statement(b, month = 1, opening = "0.0000", closing = "999.0000", number = "fremd")
                    // An unassigned statement is nobody's until it is assigned.
                    statement(null, month = 1, opening = "0.0000", closing = "5.0000", number = "unzugeordnet")
                    a.id.value
                }

            assertEquals(listOf("meins"), runBlocking { service.statementsFor(mine) }.items.map { it.statementNumber })
        }
    }

    @Test
    fun `an account with no statements returns an empty list, not an error`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val accountId = transaction { account("DE89370400440532013000").id.value }

            assertTrue(runBlocking { service.statementsFor(accountId) }.items.isEmpty())
        }
    }

    @Test
    fun `an unknown account is a 404`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            assertFailsWith<PosException> { runBlocking { service.statementsFor(999_999L) } }
            Unit
        }
    }
}
