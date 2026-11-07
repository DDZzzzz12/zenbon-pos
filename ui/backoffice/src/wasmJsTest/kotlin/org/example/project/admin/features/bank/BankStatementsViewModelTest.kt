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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun acct(
    id: Long,
    iban: String,
    gaps: List<Long> = emptyList(),
) = BankAccountDto.ResponseDto(id = id, iban = iban, bankName = "Commerzbank", continuityBreaks = gaps)

private fun stmt(
    id: Long,
    number: String,
    breakHere: Boolean = false,
) = BankAccountDto.StatementDto(statementId = id, statementNumber = number, continuityBreak = breakHere)

/**
 * The per-account statement history the Bankkonten rail exists to show.
 *
 * Selecting an account is a **fetch**, not a filter over something already loaded: the list endpoint
 * carries no statements at all, only a count and a list of break ids.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BankStatementsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `loading selects nothing, so no row reads as selected while no detail is open`() =
        runTest(dispatcher) {
            val api = FakeBankAccountApiClient()
            api.accounts = listOf(acct(1, "DE89370400440532013000"), acct(2, "DE02120300000000202051"))
            api.statements = listOf(stmt(10, "2027/01"))

            val vm = BankAccountsViewModel(api)
            vm.load()
            advanceUntilIdle()

            // NEW-08: the first account used to be auto-selected and drawn selected on a phone list.
            assertNull(vm.state.selectedAccountId)
            assertTrue(api.statementCalls.isEmpty(), "no statements are fetched before an account is picked")

            vm.selectAccount(1)
            advanceUntilIdle()
            assertEquals(listOf("2027/01"), vm.state.statements.map { it.statementNumber })
        }

    @Test
    fun `selecting another account fetches its statements`() =
        runTest(dispatcher) {
            val api = FakeBankAccountApiClient()
            api.accounts = listOf(acct(1, "DE89370400440532013000"), acct(2, "DE02120300000000202051"))
            val vm = BankAccountsViewModel(api)
            vm.load()
            advanceUntilIdle()

            vm.selectAccount(1)
            advanceUntilIdle()
            api.statements = listOf(stmt(20, "2027/02"))
            vm.selectAccount(2)
            advanceUntilIdle()

            assertEquals(2L, vm.state.selectedAccountId)
            assertEquals(listOf(1L, 2L), api.statementCalls)
            assertEquals(listOf("2027/02"), vm.state.statements.map { it.statementNumber })
        }

    /** Re-selecting must not re-request: the rail is clicked a lot and nothing has changed. */
    @Test
    fun `re-selecting the account already shown does nothing`() =
        runTest(dispatcher) {
            val api = FakeBankAccountApiClient()
            api.accounts = listOf(acct(1, "DE89370400440532013000"))
            val vm = BankAccountsViewModel(api)
            vm.load()
            advanceUntilIdle()
            vm.selectAccount(1)
            advanceUntilIdle()

            vm.selectAccount(1)
            advanceUntilIdle()

            assertEquals(listOf(1L), api.statementCalls, "one fetch, from the first selection")
        }

    /**
     * The previous account's statements must not linger under the new account's name — that would
     * attribute one account's history to another, which is the worst thing this screen could do.
     */
    @Test
    fun `switching accounts clears the previous statements before the new ones arrive`() =
        runTest(dispatcher) {
            val api = FakeBankAccountApiClient()
            api.accounts = listOf(acct(1, "DE89370400440532013000"), acct(2, "DE02120300000000202051"))
            api.statements = listOf(stmt(10, "2027/01"))
            val vm = BankAccountsViewModel(api)
            vm.load()
            advanceUntilIdle()
            vm.selectAccount(1)
            advanceUntilIdle()
            assertTrue(vm.state.statements.isNotEmpty())

            vm.selectAccount(2)

            assertTrue(vm.state.statements.isEmpty(), "cleared synchronously, before the fetch resolves")
            assertTrue(vm.state.isLoadingStatements)
        }

    @Test
    fun `an account with no statements is an empty list and not an error`() =
        runTest(dispatcher) {
            val api = FakeBankAccountApiClient()
            api.accounts = listOf(acct(1, "DE89370400440532013000"))
            api.statements = emptyList()

            val vm = BankAccountsViewModel(api)
            vm.load()
            advanceUntilIdle()
            vm.selectAccount(1)
            advanceUntilIdle()

            assertTrue(vm.state.statements.isEmpty())
            assertFalse(vm.state.isLoadingStatements)
            assertNull(vm.state.error)
        }

    @Test
    fun `no accounts means nothing is selected and nothing is fetched`() =
        runTest(dispatcher) {
            val api = FakeBankAccountApiClient()
            api.accounts = emptyList()

            val vm = BankAccountsViewModel(api)
            vm.load()
            advanceUntilIdle()

            assertNull(vm.state.selectedAccountId)
            assertTrue(api.statementCalls.isEmpty())
        }

    /** A reload after an assignment must keep the operator where they were looking. */
    @Test
    fun `reloading keeps the selected account rather than snapping back to the first`() =
        runTest(dispatcher) {
            val api = FakeBankAccountApiClient()
            api.accounts = listOf(acct(1, "DE89370400440532013000"), acct(2, "DE02120300000000202051"))
            val vm = BankAccountsViewModel(api)
            vm.load()
            advanceUntilIdle()
            vm.selectAccount(2)
            advanceUntilIdle()

            vm.load()
            advanceUntilIdle()

            assertEquals(2L, vm.state.selectedAccountId)
        }

    /** An account that vanished — deactivated, or merged away — must not leave a dangling selection. */
    @Test
    fun `a selection that no longer exists clears the selection`() =
        runTest(dispatcher) {
            val api = FakeBankAccountApiClient()
            api.accounts = listOf(acct(1, "DE89370400440532013000"), acct(2, "DE02120300000000202051"))
            val vm = BankAccountsViewModel(api)
            vm.load()
            advanceUntilIdle()
            vm.selectAccount(2)
            advanceUntilIdle()

            api.accounts = listOf(acct(1, "DE89370400440532013000"))
            vm.load()
            advanceUntilIdle()

            assertNull(vm.state.selectedAccountId, "NEW-08: nothing, not the first account")
        }

    @Test
    fun `the selected account exposes its own gap count`() =
        runTest(dispatcher) {
            val api = FakeBankAccountApiClient()
            api.accounts = listOf(acct(1, "DE89370400440532013000", gaps = listOf(11L, 12L)))
            api.statements = listOf(stmt(10, "2027/01"), stmt(11, "2027/03", breakHere = true))

            val vm = BankAccountsViewModel(api)
            vm.load()
            advanceUntilIdle()
            vm.selectAccount(1)
            advanceUntilIdle()

            val selected = vm.state.selectedAccount
            assertEquals(2, selected?.continuityBreaks?.size)
            assertEquals(1, vm.state.statements.count { it.continuityBreak })
        }
}
