package org.example.project.admin.features.bank

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.features.bank.presentation.BankAccountsViewModel
import org.example.project.core.domain.dto.bank.BankAccountDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun account(
    id: Long,
    iban: String,
) = BankAccountDto.ResponseDto(id = id, iban = iban, bankName = "Volksbank")

private fun statement(id: Long) = BankAccountDto.StatementDto(statementId = id, statementNumber = "$id")

private fun booking(
    id: Long,
    amount: String = "70.00",
) = BankAccountDto.StatementTransactionDto(
    transactionId = id,
    amount = amount,
    openAmount = amount,
    creditDebit = "DEBIT",
    counterpartyName = "Zahlung $id",
)

/**
 * Expanding a statement to see its bookings.
 *
 * The bookings are fetched per statement rather than carried on the statement list: a year of
 * statements would ship several hundred rows to show one. Each test opens an account first: the
 * list selects nothing on load (NEW-08).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatementBookingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun client() =
        FakeBankAccountApiClient().apply {
            accounts = listOf(account(1, "DE21500500009876543210"), account(2, "DE02120300000000202051"))
            statements = listOf(statement(10), statement(11))
            transactionsByStatement = mapOf(10L to listOf(booking(100), booking(101)), 11L to listOf(booking(110)))
        }

    @Test
    fun `expanding a statement fetches its bookings`() =
        runTest(dispatcher) {
            val api = client()
            val vm = BankAccountsViewModel(api)
            vm.load()
            advanceUntilIdle()
            // The list no longer auto-selects (NEW-08): open the account the way the rail does.
            vm.selectAccount(1)
            advanceUntilIdle()

            vm.toggleStatement(10)
            advanceUntilIdle()

            assertEquals(10L, vm.state.expandedStatementId)
            assertEquals(listOf(1L to 10L), api.transactionCalls)
            assertEquals(
                listOf(100L, 101L),
                vm.state.transactionsByStatement
                    .getValue(10)
                    .items
                    .map { it.transactionId },
            )
            assertNull(vm.state.loadingTransactionsFor, "the spinner must clear once the page arrives")
        }

    /** Collapsing keeps the page, so an operator comparing two statements does not pay per glance. */
    @Test
    fun `re-opening a statement does not fetch again`() =
        runTest(dispatcher) {
            val api = client()
            val vm = BankAccountsViewModel(api)
            vm.load()
            advanceUntilIdle()
            vm.selectAccount(1)
            advanceUntilIdle()

            vm.toggleStatement(10)
            advanceUntilIdle()
            vm.toggleStatement(10)
            advanceUntilIdle()
            vm.toggleStatement(10)
            advanceUntilIdle()

            assertEquals(1, api.transactionCalls.size, "the cached page was re-fetched")
        }

    /**
     * One click must be one request, even if the UI delivers it more than once.
     *
     * The cache is written only on success, so a second dispatch arriving before the first answers
     * used to see a miss and fire again. Observed in a browser as three identical GETs inside 94 ms
     * from a single click on the row. Advancing the dispatcher only after all three toggles is what
     * reproduces it — with an `advanceUntilIdle` between them the cache is already warm and the bug
     * hides.
     */
    @Test
    fun `repeated toggles before the first answers still fetch once`() =
        runTest(dispatcher) {
            val api = client()
            val vm = BankAccountsViewModel(api)
            vm.load()
            advanceUntilIdle()
            vm.selectAccount(1)
            advanceUntilIdle()

            vm.toggleStatement(10)
            vm.toggleStatement(10)
            vm.toggleStatement(10)
            advanceUntilIdle()

            assertEquals(1, api.transactionCalls.size, "a single expansion fired more than one request")
            assertEquals(10L, vm.state.expandedStatementId)
        }

    @Test
    fun `opening a second statement closes the first`() =
        runTest(dispatcher) {
            val vm = BankAccountsViewModel(client())
            vm.load()
            advanceUntilIdle()
            vm.selectAccount(1)
            advanceUntilIdle()

            vm.toggleStatement(10)
            advanceUntilIdle()
            vm.toggleStatement(11)
            advanceUntilIdle()

            assertEquals(11L, vm.state.expandedStatementId)
            // Both pages stay cached — only one is on screen.
            assertEquals(setOf(10L, 11L), vm.state.transactionsByStatement.keys)
        }

    @Test
    fun `collapsing clears the expansion without clearing the cache`() =
        runTest(dispatcher) {
            val vm = BankAccountsViewModel(client())
            vm.load()
            advanceUntilIdle()
            vm.selectAccount(1)
            advanceUntilIdle()

            vm.toggleStatement(10)
            advanceUntilIdle()
            vm.toggleStatement(10)
            advanceUntilIdle()

            assertNull(vm.state.expandedStatementId)
            assertTrue(vm.state.transactionsByStatement.containsKey(10))
        }

    /**
     * The cache must not survive the account it belongs to.
     *
     * Statement ids do not repeat across accounts, so nothing would visibly break — but the same rule
     * already clears `statements` here, and the reason is the one thing this screen must never do:
     * show one account's data under another account's name.
     */
    @Test
    fun `switching account drops the expansion and the cached bookings`() =
        runTest(dispatcher) {
            val vm = BankAccountsViewModel(client())
            vm.load()
            advanceUntilIdle()
            vm.selectAccount(1)
            advanceUntilIdle()
            vm.toggleStatement(10)
            advanceUntilIdle()

            vm.selectAccount(2)
            advanceUntilIdle()

            assertNull(vm.state.expandedStatementId)
            assertEquals(emptyMap(), vm.state.transactionsByStatement)
        }

    /**
     * A failed fetch collapses the row rather than leaving it open over nothing.
     *
     * An expanded row showing neither bookings nor a spinner reads as "this statement is empty",
     * which is a different — and wrong — statement about the operator's books.
     */
    @Test
    fun `a failed fetch collapses the row and surfaces the error`() =
        runTest(dispatcher) {
            val api = client()
            val vm = BankAccountsViewModel(api)
            vm.load()
            advanceUntilIdle()
            vm.selectAccount(1)
            advanceUntilIdle()

            api.failWith = PosApiException(PosErrorCode.INTERNAL_ERROR, 500, "boom", null)
            vm.toggleStatement(10)
            advanceUntilIdle()

            assertNull(vm.state.expandedStatementId)
            assertNull(vm.state.loadingTransactionsFor)
            assertTrue(vm.state.error != null, "the operator must be told the fetch failed")
        }

    /** The whole count travels with the page, so the UI can say a statement holds more than it shows. */
    @Test
    fun `the page carries the statement's whole count, not the page size`() =
        runTest(dispatcher) {
            val api = client().apply { transactionTotalOverride = 214L }
            val vm = BankAccountsViewModel(api)
            vm.load()
            advanceUntilIdle()
            vm.selectAccount(1)
            advanceUntilIdle()

            vm.toggleStatement(10)
            advanceUntilIdle()

            val page = vm.state.transactionsByStatement.getValue(10)
            assertEquals(2, page.items.size)
            assertEquals(214L, page.total)
        }
}
