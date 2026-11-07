package org.example.project.admin.features.debtors

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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

private fun row(
    id: Long,
    name: String = "Acme GmbH",
    active: Boolean = true,
) = DebtorDto.ResponseDto(id = id, name = name, isActive = active)

/**
 * The Debitoren search is applied server-side, so the resulting state cannot prove the ViewModel
 * asked for it — these assert on what reached the API client.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DebtorSearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(api: FakeDebtorApiClient) = DebtorsViewModel(api, FakeSalesInvoiceApiClient())

    @Test
    fun `typing echoes immediately but requests only once it settles`() =
        runTest(dispatcher) {
            val api = FakeDebtorApiClient().apply { debtors = listOf(row(1)) }
            val vm = viewModel(api)
            vm.load()
            advanceUntilIdle()

            vm.updateSearch("acm")
            assertEquals("acm", vm.state.search, "the field must echo the keystroke immediately")
            advanceTimeBy(100)
            assertNull(api.lastSearch, "no request while the operator is still typing")

            advanceTimeBy(400)
            advanceUntilIdle()
            assertEquals("acm", api.lastSearch)
        }

    @Test
    fun `keystrokes in quick succession produce one request, for the final term`() =
        runTest(dispatcher) {
            val api = FakeDebtorApiClient().apply { debtors = listOf(row(1)) }
            val vm = viewModel(api)
            vm.load()
            advanceUntilIdle()
            val callsAfterLoad = api.listCalls

            listOf("a", "ac", "acm", "acme").forEach {
                vm.updateSearch(it)
                advanceTimeBy(50)
            }
            advanceTimeBy(400)
            advanceUntilIdle()

            assertEquals(callsAfterLoad + 1, api.listCalls)
            assertEquals("acme", api.lastSearch)
        }

    @Test
    fun `a blank term is sent as null rather than as spaces`() =
        runTest(dispatcher) {
            val api = FakeDebtorApiClient().apply { debtors = listOf(row(1)) }
            viewModel(api).updateSearch("   ")
            advanceTimeBy(500)
            advanceUntilIdle()

            assertNull(api.lastSearch)
        }

    /** The chip and the box are both applied filters; neither may drop the other. */
    @Test
    fun `switching the active chip keeps the search term`() =
        runTest(dispatcher) {
            val api = FakeDebtorApiClient().apply { debtors = listOf(row(1)) }
            val vm = viewModel(api)
            vm.updateSearch("acme")
            advanceTimeBy(500)
            advanceUntilIdle()

            vm.setActiveOnly(true)
            advanceUntilIdle()

            assertEquals("acme", api.lastSearch)
            assertEquals(true, api.lastActiveOnly)
        }

    @Test
    fun `resetFilters clears both the term and the chip in one request`() =
        runTest(dispatcher) {
            val api = FakeDebtorApiClient().apply { debtors = listOf(row(1)) }
            val vm = viewModel(api)
            vm.updateSearch("acme")
            advanceTimeBy(500)
            advanceUntilIdle()
            vm.setActiveOnly(true)
            advanceUntilIdle()
            val callsBefore = api.listCalls

            vm.resetFilters()
            advanceUntilIdle()

            assertEquals(callsBefore + 1, api.listCalls, "one request, not one per filter")
            assertNull(api.lastSearch)
            assertEquals(false, api.lastActiveOnly)
            assertFalse(vm.state.hasActiveFilter)
        }

    @Test
    fun `hasActiveFilter reflects either narrowing`() =
        runTest(dispatcher) {
            val api = FakeDebtorApiClient().apply { debtors = listOf(row(1)) }
            val vm = viewModel(api)
            assertFalse(vm.state.hasActiveFilter)

            vm.updateSearch("acme")
            assertTrue(vm.state.hasActiveFilter, "a typed term counts before it is even sent")
            advanceTimeBy(500)
            advanceUntilIdle()
        }
}
