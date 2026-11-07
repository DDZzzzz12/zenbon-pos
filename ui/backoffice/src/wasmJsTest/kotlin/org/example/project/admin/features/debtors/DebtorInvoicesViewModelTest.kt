package org.example.project.admin.features.debtors

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.features.debtors.presentation.DebtorsViewModel
import org.example.project.admin.features.salesinvoices.FakeSalesInvoiceApiClient
import org.example.project.core.domain.dto.debtor.DebtorDto
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun debtor(
    id: Long,
    name: String = "Acme GmbH",
    active: Boolean = true,
) = DebtorDto.ResponseDto(id = id, name = name, isActive = active)

/**
 * The debtor detail pane's invoices — the link Debitoren and Ausgangsrechnungen were missing.
 *
 * The invoices are **fetched per debtor** through the list endpoint's `debtorId` filter rather than
 * narrowed from a page of all invoices: that list is paged newest-first, so a debtor whose last
 * invoice fell outside the page would show as having none.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DebtorInvoicesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        debtors: FakeDebtorApiClient,
        invoices: FakeSalesInvoiceApiClient,
    ) = DebtorsViewModel(debtors, invoices)

    @Test
    fun `loading selects nothing, so no row reads as selected while no detail is open`() =
        runTest(dispatcher) {
            val debtors = FakeDebtorApiClient().apply { this.debtors = listOf(debtor(1), debtor(2, "Beta AG")) }
            val invoices = FakeSalesInvoiceApiClient()

            val vm = viewModel(debtors, invoices)
            vm.load()
            advanceUntilIdle()

            // NEW-08: the first row used to be auto-selected and drawn selected on a phone list.
            assertNull(vm.state.selectedDebtorId)
            assertNull(invoices.lastDebtorId, "no invoices are fetched before a debtor is picked")
        }

    @Test
    fun `selecting a debtor asks the server for that debtor's invoices`() =
        runTest(dispatcher) {
            val debtors = FakeDebtorApiClient().apply { this.debtors = listOf(debtor(1), debtor(2, "Beta AG")) }
            val invoices = FakeSalesInvoiceApiClient()
            val vm = viewModel(debtors, invoices)
            vm.load()
            advanceUntilIdle()

            vm.selectDebtor(2)
            advanceUntilIdle()

            assertEquals(2L, vm.state.selectedDebtorId)
            assertEquals(2L, invoices.lastDebtorId)
        }

    /** The whole point of the filter: it must reach the request, not be applied afterwards. */
    @Test
    fun `the invoices request carries no status filter, so drafts and storno show too`() =
        runTest(dispatcher) {
            val debtors = FakeDebtorApiClient().apply { this.debtors = listOf(debtor(1)) }
            val invoices = FakeSalesInvoiceApiClient()

            val vm = viewModel(debtors, invoices).also { it.load() }
            advanceUntilIdle()
            vm.selectDebtor(1)
            advanceUntilIdle()

            assertNull(invoices.lastStatus)
            assertEquals(false, invoices.lastOpen)
            assertEquals(false, invoices.lastOverdue)
        }

    /**
     * One debtor's invoices must never be shown under another's name, so the previous list is
     * dropped before the new one is even requested.
     */
    @Test
    fun `switching debtors clears the previous invoices before the new ones arrive`() =
        runTest(dispatcher) {
            val debtors = FakeDebtorApiClient().apply { this.debtors = listOf(debtor(1), debtor(2, "Beta AG")) }
            val invoices = FakeSalesInvoiceApiClient()
            val vm = viewModel(debtors, invoices)
            vm.load()
            advanceUntilIdle()
            vm.selectDebtor(1)
            advanceUntilIdle()

            vm.selectDebtor(2)

            assertTrue(vm.state.invoices.isEmpty())
            assertTrue(vm.state.isLoadingInvoices)
        }

    @Test
    fun `re-selecting the debtor already shown does not re-request`() =
        runTest(dispatcher) {
            val debtors = FakeDebtorApiClient().apply { this.debtors = listOf(debtor(1)) }
            val invoices = FakeSalesInvoiceApiClient()
            val vm = viewModel(debtors, invoices)
            vm.load()
            advanceUntilIdle()
            vm.selectDebtor(1)
            advanceUntilIdle()
            invoices.lastDebtorId = null

            vm.selectDebtor(1)
            advanceUntilIdle()

            assertNull(invoices.lastDebtorId, "no second request for the same debtor")
        }

    @Test
    fun `no debtors means nothing is selected and no invoices are fetched`() =
        runTest(dispatcher) {
            val debtors = FakeDebtorApiClient()
            val invoices = FakeSalesInvoiceApiClient()

            val vm = viewModel(debtors, invoices)
            vm.load()
            advanceUntilIdle()

            assertNull(vm.state.selectedDebtorId)
            assertNull(invoices.lastDebtorId)
        }

    @Test
    fun `reloading keeps the selected debtor rather than snapping back to the first`() =
        runTest(dispatcher) {
            val debtors = FakeDebtorApiClient().apply { this.debtors = listOf(debtor(1), debtor(2, "Beta AG")) }
            val invoices = FakeSalesInvoiceApiClient()
            val vm = viewModel(debtors, invoices)
            vm.load()
            advanceUntilIdle()
            vm.selectDebtor(2)
            advanceUntilIdle()

            vm.load()
            advanceUntilIdle()

            assertEquals(2L, vm.state.selectedDebtorId)
        }

    /** A debtor filtered out by the active chip must not leave the pane pointing at it (NEW-08: nothing, not the first row). */
    @Test
    fun `a selection the filter removed clears the selection`() =
        runTest(dispatcher) {
            val debtors =
                FakeDebtorApiClient().apply {
                    this.debtors = listOf(debtor(1), debtor(2, "Beta AG", active = false))
                }
            val invoices = FakeSalesInvoiceApiClient()
            val vm = viewModel(debtors, invoices)
            vm.load()
            advanceUntilIdle()
            vm.selectDebtor(2)
            advanceUntilIdle()

            vm.setActiveOnly(true)
            advanceUntilIdle()

            assertNull(vm.state.selectedDebtorId)
        }

    @Test
    fun `a debtor with no invoices is an empty list and not an error`() =
        runTest(dispatcher) {
            val debtors = FakeDebtorApiClient().apply { this.debtors = listOf(debtor(1)) }
            val invoices = FakeSalesInvoiceApiClient()

            val vm = viewModel(debtors, invoices)
            vm.load()
            advanceUntilIdle()
            vm.selectDebtor(1)
            advanceUntilIdle()

            assertTrue(vm.state.invoices.isEmpty())
            assertFalse(vm.state.isLoadingInvoices)
            assertNull(vm.state.error)
        }
}
