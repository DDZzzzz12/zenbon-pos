package org.example.project.admin.features.bank

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.bank.presentation.BankAccountsViewModel
import org.example.project.core.domain.dto.bank.BankAccountDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun account(
    id: Long,
    iban: String = "DE89370400440532013000",
    gaps: List<Long> = emptyList(),
) = BankAccountDto.ResponseDto(id = id, iban = iban, bankName = "Commerzbank", continuityBreaks = gaps)

private fun unassigned(statementId: Long) = BankAccountDto.UnassignedStatementDto(statementId = statementId, assertedIban = "DE00111122223333444455")

@OptIn(ExperimentalCoroutinesApi::class)
class BankAccountsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `load fills accounts and unassigned statements`() =
        runTest(dispatcher) {
            val api = FakeBankAccountApiClient()
            api.accounts = listOf(account(1))
            api.unassigned = listOf(unassigned(7))

            val vm = BankAccountsViewModel(api)
            vm.load()
            advanceUntilIdle()

            assertEquals(1, vm.state.accounts.size)
            assertEquals(listOf(7L), vm.state.unassigned.map { it.statementId })
            assertFalse(vm.state.isLoading)
            assertNull(vm.state.error)
        }

    @Test
    fun `assigning a statement reloads so it leaves the unassigned list`() =
        runTest(dispatcher) {
            val api = FakeBankAccountApiClient()
            api.accounts = listOf(account(1))
            api.unassigned = listOf(unassigned(7))

            val vm = BankAccountsViewModel(api)
            vm.load()
            advanceUntilIdle()

            api.unassigned = emptyList()
            vm.assignStatement(statementId = 7, bankAccountId = 1)
            advanceUntilIdle()

            assertEquals(listOf(7L to 1L), api.assignments)
            assertTrue(vm.state.unassigned.isEmpty(), "the list must be re-read, not mutated locally")
        }

    @Test
    fun `creating an account with a blank IBAN never reaches the API`() =
        runTest(dispatcher) {
            val api = FakeBankAccountApiClient()
            val vm = BankAccountsViewModel(api)
            vm.load()
            advanceUntilIdle()

            vm.createAccount(BankAccountDto.CreateRequestDto(iban = "   "))
            advanceUntilIdle()

            assertEquals(AdminStringKeys.BankErrorIbanRequired, vm.state.createError)
            assertTrue(api.creates.isEmpty())
        }

    @Test
    fun `a taken IBAN surfaces the actionable message`() =
        runTest(dispatcher) {
            val api = FakeBankAccountApiClient()
            val vm = BankAccountsViewModel(api)
            vm.load()
            advanceUntilIdle()

            api.failWith = PosApiException(PosErrorCode.BANK_ACCOUNT_IBAN_TAKEN, 409, "taken", null)
            vm.createAccount(BankAccountDto.CreateRequestDto(iban = "DE89370400440532013000"))
            advanceUntilIdle()

            assertEquals(AdminStringKeys.BankErrorIbanTaken, vm.state.createError)
        }

    /**
     * E2E 2026-09-27: "Neues Bankkonto" stayed open after a successful create. The screen inferred
     * success from an isBusy true→false flip that composition never saw when the server answered
     * within a frame. The VM now owns open/closed and closes it itself on success.
     */
    @Test
    fun `a successful create closes the dialog, a refused one keeps it open`() =
        runTest(dispatcher) {
            val api = FakeBankAccountApiClient()
            val vm = BankAccountsViewModel(api)
            vm.load()
            advanceUntilIdle()

            vm.openCreateDialog()
            assertTrue(vm.state.isCreateDialogOpen)

            vm.createAccount(BankAccountDto.CreateRequestDto(iban = "   "))
            advanceUntilIdle()
            assertTrue(vm.state.isCreateDialogOpen, "a blank IBAN is a field error next to the input")

            api.failWith = PosApiException(PosErrorCode.BANK_ACCOUNT_IBAN_TAKEN, 409, "taken", null)
            vm.createAccount(BankAccountDto.CreateRequestDto(iban = "DE89370400440532013000"))
            advanceUntilIdle()
            assertTrue(vm.state.isCreateDialogOpen, "a taken IBAN keeps the dialog open")

            api.failWith = null
            vm.createAccount(BankAccountDto.CreateRequestDto(iban = "DE02120300000000202051"))
            advanceUntilIdle()
            assertFalse(vm.state.isCreateDialogOpen, "accepted by the server: the dialog closes")
            assertNull(vm.state.createError)
            assertEquals(2, api.creates.size, "the refused attempt and the accepted one")
        }

    @Test
    fun `dismissing the create dialog drops its field error`() =
        runTest(dispatcher) {
            val vm = BankAccountsViewModel(FakeBankAccountApiClient())
            vm.openCreateDialog()
            vm.createAccount(BankAccountDto.CreateRequestDto(iban = ""))
            assertNotNull(vm.state.createError)

            vm.dismissCreateDialog()

            assertFalse(vm.state.isCreateDialogOpen)
            assertNull(vm.state.createError, "reopening starts clean")
        }

    @Test
    fun `resolve reports how many statements it assigned`() =
        runTest(dispatcher) {
            val api = FakeBankAccountApiClient()
            api.resolvedCount = 3

            val vm = BankAccountsViewModel(api)
            vm.load()
            advanceUntilIdle()
            vm.resolveUnassigned()
            advanceUntilIdle()

            assertEquals(1, api.resolveCalls)
            assertEquals(3, vm.state.lastResolvedCount)
            assertEquals(2, api.listCalls, "resolve must re-read the list")
        }

    @Test
    fun `a load failure surfaces a typed error and stops loading`() =
        runTest(dispatcher) {
            val api = FakeBankAccountApiClient()
            api.failWith = PosApiException(PosErrorCode.INTERNAL_ERROR, 500, "boom", null)

            val vm = BankAccountsViewModel(api)
            vm.load()
            advanceUntilIdle()

            assertNotNull(vm.state.error)
            assertFalse(vm.state.isLoading)
        }
}
