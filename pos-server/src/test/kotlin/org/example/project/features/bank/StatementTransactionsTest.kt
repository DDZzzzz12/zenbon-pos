package org.example.project.features.bank

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.features.bank.data.repository.BankAccountRepositoryImpl
import org.example.project.features.bank.data.service.BankAccountServiceImpl
import org.example.project.features.bank.data.service.StatementAccountResolver
import org.example.project.features.bank.domain.models.BankAccount
import org.example.project.features.document.domain.models.BankStatement
import org.example.project.features.document.domain.models.BankStatementTransaction
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * `GET /api/bank-accounts/{id}/statements/{statementId}/transactions` — the bookings behind a
 * statement, which Bankkonten names by count and now lets an operator open.
 */
@DisplayName("Statement transactions")
class StatementTransactionsTest {
    private val repository = BankAccountRepositoryImpl()
    private val service = BankAccountServiceImpl(repository, StatementAccountResolver(repository))

    private fun account(iban: String): BankAccount =
        repository.create(
            shop = ShopInfo.all().first(),
            iban = iban,
            bic = null,
            bankName = "Commerzbank",
        )

    private fun statement(account: BankAccount?): BankStatement =
        BankStatement.new {
            this.bankAccount = account
            this.statementDate = LocalDateTime(2027, 3, 31, 0, 0)
            this.periodStart = LocalDateTime(2027, 3, 1, 0, 0)
            this.periodEnd = LocalDateTime(2027, 3, 31, 0, 0)
            this.createdAt = LocalDateTime(2027, 3, 31, 0, 0)
        }

    private fun booking(
        statement: BankStatement,
        day: Int,
        amount: String,
        open: String? = null,
        direction: String = "DEBIT",
        counterparty: String? = null,
        remittance: String? = null,
    ): BankStatementTransaction =
        BankStatementTransaction.new {
            this.statement = statement
            this.bookingDate = LocalDateTime(2027, 3, day, 0, 0)
            this.valueDate = LocalDateTime(2027, 3, day, 0, 0)
            this.amount = BigDecimal(amount)
            this.openAmount = open?.let { BigDecimal(it) }
            this.creditDebit = direction
            this.counterpartyName = counterparty
            this.remittanceUnstructured = remittance
        }

    @Test
    fun `bookings come back oldest first, with what each one still owes`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val statementId =
                transaction {
                    val acc = account("DE89370400440532013000")
                    val stmt = statement(acc)
                    booking(stmt, day = 18, amount = "70.0000", counterparty = "Beitragsservice")
                    booking(stmt, day = 4, amount = "220.0000", open = "20.0000", counterparty = "Lieferant Mai GmbH")
                    stmt.id.value
                }

            val page = runBlocking { service.statementTransactions(1L, statementId, limit = 100, offset = 0) }

            assertEquals(listOf("Lieferant Mai GmbH", "Beitragsservice"), page.items.map { it.counterpartyName })
            assertEquals(2, page.total)
            // 220,00 with 20,00 left; 70,00 with nothing computed, so the whole amount is still open.
            assertEquals(listOf("20.00", "70.00"), page.items.map { it.openAmount })
            assertEquals(listOf("220.00", "70.00"), page.items.map { it.amount })
        }
    }

    /**
     * The residual falls back to the full amount, the same rule the Zuordnung queue applies.
     *
     * `open_amount` is NULL on every booking `:accounting-banking` parses — nothing writes it at ingest — so
     * reading the column alone would render every freshly imported statement as fully settled. That
     * is the same defect the creditor list carried against invoices.
     */
    @Test
    fun `a booking with no residual written is fully open, not settled`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val statementId =
                transaction {
                    val stmt = statement(account("DE89370400440532013000"))
                    booking(stmt, day = 2, amount = "349.9900")
                    stmt.id.value
                }

            val page = runBlocking { service.statementTransactions(1L, statementId, limit = 100, offset = 0) }

            assertEquals("349.99", page.items.single().openAmount)
        }
    }

    /**
     * The account in the path is a guard, not decoration.
     *
     * Without it anyone holding `CREDITOR_READ` reads any account's bookings by guessing a statement
     * id. It answers 404 rather than 403, because saying "forbidden" would confirm the id is real.
     */
    @Test
    fun `a statement belonging to another account is a 404`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val (mineId, theirStatementId) =
                transaction {
                    val mine = account("DE89370400440532013000")
                    val theirs = account("DE02120300000000202051")
                    val stmt = statement(theirs)
                    booking(stmt, day = 5, amount = "10.0000")
                    mine.id.value to stmt.id.value
                }

            assertFailsWith<PosException> {
                runBlocking { service.statementTransactions(mineId, theirStatementId, limit = 100, offset = 0) }
            }
            Unit
        }
    }

    /** An unassigned statement belongs to no account, so no account may read it either. */
    @Test
    fun `a statement no account claims is a 404`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val (accountId, orphanId) =
                transaction {
                    val acc = account("DE89370400440532013000")
                    acc.id.value to statement(account = null).id.value
                }

            assertFailsWith<PosException> {
                runBlocking { service.statementTransactions(accountId, orphanId, limit = 100, offset = 0) }
            }
            Unit
        }
    }

    /**
     * Paging walks the statement once.
     *
     * The value-date order needs the id tiebreak to be stable: these five bookings deliberately share
     * two dates, so an engine left to its own ordering could repeat or skip one across the boundary.
     */
    @Test
    fun `paging returns each booking exactly once and keeps the total whole`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val statementId =
                transaction {
                    val stmt = statement(account("DE89370400440532013000"))
                    listOf(1, 1, 2, 2, 3).forEachIndexed { index, day ->
                        booking(stmt, day = day, amount = "10.0000", counterparty = "Zahlung $index")
                    }
                    stmt.id.value
                }

            val first = runBlocking { service.statementTransactions(1L, statementId, limit = 2, offset = 0) }
            val second = runBlocking { service.statementTransactions(1L, statementId, limit = 2, offset = 2) }
            val third = runBlocking { service.statementTransactions(1L, statementId, limit = 2, offset = 4) }

            val walked = (first.items + second.items + third.items).map { it.transactionId }
            assertEquals(5, walked.size)
            assertEquals(5, walked.distinct().size, "a page boundary repeated a booking")
            // Every page reports the statement's whole count, not its own.
            assertEquals(listOf(5L, 5L, 5L), listOf(first.total, second.total, third.total))
            assertEquals(listOf(0L, 2L, 4L), listOf(first.offset, second.offset, third.offset))
        }
    }

    @Test
    fun `a statement with no bookings is an empty page, not an error`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val statementId = transaction { statement(account("DE89370400440532013000")).id.value }

            val page = runBlocking { service.statementTransactions(1L, statementId, limit = 100, offset = 0) }

            assertEquals(0, page.total)
            assertEquals(emptyList(), page.items)
        }
    }

    /** A booking nobody re-delivered carries no duplicate hint — the flag must not default to a value. */
    @Test
    fun `a unique booking reports no duplicate`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            val statementId =
                transaction {
                    val stmt = statement(account("DE89370400440532013000"))
                    booking(stmt, day = 9, amount = "12.5000")
                    stmt.id.value
                }

            val page = runBlocking { service.statementTransactions(1L, statementId, limit = 100, offset = 0) }

            assertNull(page.items.single().duplicateOfTransactionId)
        }
    }
}
