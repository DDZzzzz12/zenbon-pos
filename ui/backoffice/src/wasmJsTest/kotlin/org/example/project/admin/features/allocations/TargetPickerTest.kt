package org.example.project.admin.features.allocations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.features.allocations.presentation.AllocationsViewModel
import org.example.project.admin.features.allocations.presentation.draftKey
import org.example.project.admin.features.bank.FakeBankAccountApiClient
import org.example.project.admin.features.dashboard.FakeKassenbuchApiClient
import org.example.project.core.domain.dto.allocation.AllocationDto
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two counter-leg targets: a *Geldtransit* (till cash deposited at the bank) and an
 * *Eigenübertrag* between two of our own accounts.
 *
 * Both name the other half of the movement, and both are refused here without one — the server
 * answers 400 for the same case, and this is that guard's client half (root `CLAUDE.md` rule 8).
 * Money that left one place without recording where it arrived is not a booking, it is a hole.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TargetPickerTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun allocations(): FakeAllocationApiClient =
        FakeAllocationApiClient().apply {
            rowsByFilter = mapOf(AllocationDto.QueueFilter.NICHT_ZUGEORDNET to listOf(queueRow(7)))
        }

    private fun banks(): FakeBankAccountApiClient = FakeBankAccountApiClient().apply { accounts = listOf(bankAccount(4)) }

    private fun cash(): FakeKassenbuchApiClient = FakeKassenbuchApiClient(entries = { listOf(cashEntry(9)) }, balance = { "0.00" })

    /** Expands the one queue row and selects the kind — where every test below starts. */
    private fun TestScope.expanded(
        vm: AllocationsViewModel,
        kind: String,
    ) {
        vm.load()
        advanceUntilIdle()
        vm.toggleExpanded(7)
        advanceUntilIdle()
        vm.selectTargetKind(kind)
        advanceUntilIdle()
    }

    @Test
    fun `selecting the transfer kind loads the bank accounts`() =
        runTest(dispatcher) {
            val bank = banks()
            val vm = allocationsViewModel(api = allocations(), bankAccounts = bank)
            expanded(vm, "INTERNAL_TRANSFER")

            assertEquals(1, bank.listCalls)
            assertEquals(1, vm.state.bankAccounts.size)

            // Away and back: re-selecting the active chip returns early on its own, so only a round
            // trip through another kind can prove the loaded list is what stops the second read.
            vm.selectTargetKind("FEE")
            vm.selectTargetKind("INTERNAL_TRANSFER")
            advanceUntilIdle()

            assertEquals(1, bank.listCalls, "the loaded accounts are kept for the expanded row")
        }

    @Test
    fun `assigning an internal transfer posts the counter account id`() =
        runTest(dispatcher) {
            val api = allocations()
            val vm = allocationsViewModel(api = api, bankAccounts = banks())
            expanded(vm, "INTERNAL_TRANSFER")
            vm.selectCounter(4)
            vm.setAmount(draftKey("INTERNAL_TRANSFER", null), "100,00")

            vm.allocateTransfer(transactionId = 7)
            advanceUntilIdle()

            val request = api.created.single()
            assertEquals("INTERNAL_TRANSFER", request.targetKind)
            assertEquals(7L, request.bankTransactionId)
            assertEquals(4L, request.counterBankAccountId)
            assertEquals("100.00", request.amount)
            assertNull(request.invoiceId)
            assertNull(request.contractInstalmentId)
        }

    @Test
    fun `an internal transfer with no counter account is not posted`() =
        runTest(dispatcher) {
            val api = allocations()
            val vm = allocationsViewModel(api = api, bankAccounts = banks())
            expanded(vm, "INTERNAL_TRANSFER")
            vm.setAmount(draftKey("INTERNAL_TRANSFER", null), "100,00")

            vm.allocateTransfer(transactionId = 7)
            advanceUntilIdle()

            assertTrue(api.created.isEmpty(), "the server refuses this with a 400 — so must the client")
        }

    @Test
    fun `selecting the transit kind loads the recent cash entries`() =
        runTest(dispatcher) {
            val kassenbuch = cash()
            val vm = allocationsViewModel(api = allocations(), cashEntries = kassenbuch)
            expanded(vm, "CASH_TRANSIT")

            assertEquals(1, kassenbuch.recentEntriesCalls)
            assertEquals(1, vm.state.cashEntries.size)

            vm.selectTargetKind("FEE")
            vm.selectTargetKind("CASH_TRANSIT")
            advanceUntilIdle()

            assertEquals(1, kassenbuch.recentEntriesCalls, "the loaded entries are kept for the expanded row")
        }

    @Test
    fun `assigning a Geldtransit posts the counter Kassenbuch header id`() =
        runTest(dispatcher) {
            val api = allocations()
            val vm = allocationsViewModel(api = api, cashEntries = cash())
            expanded(vm, "CASH_TRANSIT")
            vm.selectCounter(9)
            vm.setAmount(draftKey("CASH_TRANSIT", null), "100,00")

            vm.allocateTransit(transactionId = 7)
            advanceUntilIdle()

            val request = api.created.single()
            assertEquals("CASH_TRANSIT", request.targetKind)
            assertEquals(7L, request.bankTransactionId)
            assertEquals(9L, request.counterKassenbuchHeaderId)
            assertEquals("100.00", request.amount)
            assertNull(request.invoiceId)
            assertNull(request.contractInstalmentId)
        }

    /**
     * Switching kind must drop the chosen counter-leg — the one clearing whose regression is silent
     * *and* writes a wrong fiscal record.
     *
     * The two counter-leg kinds pick their candidates from different tables but share one
     * `selectedCounterId`. Carry bank account 9 over from an *Eigenübertrag* into a *Geldtransit*
     * and it is posted as `counterKassenbuchHeaderId` — a value with no relation to a cash book.
     * Nothing catches it: `KassenbuchHeader.findById(9)` succeeds whenever *any* header has that id,
     * so the server writes a Geldtransit bound to an unrelated cash-book entry and no layer reports
     * an error. The other drafts this switch clears only cost a retyped number.
     */
    @Test
    fun `switching the target kind drops the chosen counter-leg`() =
        runTest(dispatcher) {
            val vm = allocationsViewModel(api = allocations(), bankAccounts = banks(), cashEntries = cash())
            expanded(vm, "INTERNAL_TRANSFER")
            vm.selectCounter(4)
            assertEquals(4L, vm.state.selectedCounterId)

            vm.selectTargetKind("CASH_TRANSIT")
            advanceUntilIdle()

            assertNull(
                vm.state.selectedCounterId,
                "a bank account id must not survive into a Geldtransit's Kassenbuch header id",
            )
        }

    @Test
    fun `a Geldtransit with no cash entry chosen is not posted`() =
        runTest(dispatcher) {
            val api = allocations()
            val vm = allocationsViewModel(api = api, cashEntries = cash())
            expanded(vm, "CASH_TRANSIT")
            vm.setAmount(draftKey("CASH_TRANSIT", null), "100,00")

            vm.allocateTransit(transactionId = 7)
            advanceUntilIdle()

            assertTrue(api.created.isEmpty(), "the server refuses this with a 400 — so must the client")
        }
}
