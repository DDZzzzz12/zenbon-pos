package org.example.project.admin.features.supplier

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.features.supplier.presentation.SupplierSortColumn
import org.example.project.admin.features.supplier.presentation.SuppliersViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Search and ordering are applied **server-side**, so the resulting state cannot prove the ViewModel
 * asked for them — these assert on what reached the API client.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SuppliersSearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `typing echoes immediately but requests only once it settles`() =
        runTest(dispatcher) {
            val api = FakeSupplierApiClient()
            val vm = SuppliersViewModel(api)
            vm.load()
            advanceUntilIdle()
            val callsAfterLoad = api.listCalls.size

            vm.updateSearch("met")
            assertEquals("met", vm.state.search, "the field must echo the keystroke immediately")
            advanceTimeBy(100)
            assertEquals(callsAfterLoad, api.listCalls.size, "no request while the operator is still typing")

            advanceTimeBy(400)
            advanceUntilIdle()
            assertEquals(callsAfterLoad + 1, api.listCalls.size)
            assertEquals("met", api.lastSearch)
        }

    /**
     * The debounce is not cosmetic. Without cancelling the pending job, five keystrokes are five
     * paged requests whose responses can land out of order — and then the *last response* wins
     * rather than the last request, so the list shows results for a prefix of what is in the box.
     */
    @Test
    fun `keystrokes in quick succession produce one request, for the final term`() =
        runTest(dispatcher) {
            val api = FakeSupplierApiClient()
            val vm = SuppliersViewModel(api)
            vm.load()
            advanceUntilIdle()
            val callsAfterLoad = api.listCalls.size

            listOf("m", "me", "met", "metr", "metro").forEach {
                vm.updateSearch(it)
                advanceTimeBy(50)
            }
            advanceTimeBy(400)
            advanceUntilIdle()

            assertEquals(callsAfterLoad + 1, api.listCalls.size)
            assertEquals("metro", api.lastSearch)
        }

    @Test
    fun `clearing the search re-requests without a term`() =
        runTest(dispatcher) {
            val api = FakeSupplierApiClient()
            val vm = SuppliersViewModel(api)
            vm.updateSearch("metro")
            advanceTimeBy(500)
            advanceUntilIdle()
            vm.updateSearch("")
            advanceTimeBy(500)
            advanceUntilIdle()

            assertTrue(api.lastSearch.isNullOrEmpty(), "an emptied box widens the list, it does not filter on nothing")
        }

    @Test
    fun `a blank term is sent as null rather than as spaces`() =
        runTest(dispatcher) {
            val api = FakeSupplierApiClient()
            val vm = SuppliersViewModel(api)
            vm.updateSearch("   ")
            advanceTimeBy(500)
            advanceUntilIdle()

            assertEquals(null, api.lastSearch)
        }

    @Test
    fun `sorting the same column twice reverses it`() =
        runTest(dispatcher) {
            val api = FakeSupplierApiClient()
            val vm = SuppliersViewModel(api)

            vm.sortBy(SupplierSortColumn.VAT_ID)
            advanceTimeBy(1)
            advanceUntilIdle()
            assertEquals("VAT_ID", api.lastSort)
            assertTrue(api.lastAscending)

            vm.sortBy(SupplierSortColumn.VAT_ID)
            advanceTimeBy(1)
            advanceUntilIdle()
            assertFalse(api.lastAscending)
        }

    @Test
    fun `sorting a different column starts ascending again`() =
        runTest(dispatcher) {
            val api = FakeSupplierApiClient()
            val vm = SuppliersViewModel(api)

            vm.sortBy(SupplierSortColumn.VAT_ID)
            advanceTimeBy(1)
            advanceUntilIdle()
            vm.sortBy(SupplierSortColumn.VAT_ID)
            advanceTimeBy(1)
            advanceUntilIdle()
            vm.sortBy(SupplierSortColumn.NAME)
            advanceTimeBy(1)
            advanceUntilIdle()

            assertEquals("NAME", api.lastSort)
            assertTrue(api.lastAscending, "a new column is not a continuation of the previous column's direction")
        }

    /** The chip filter and the search term are both applied filters; one must not drop the other. */
    @Test
    fun `switching the provisional chip keeps the search term`() =
        runTest(dispatcher) {
            val api = FakeSupplierApiClient()
            val vm = SuppliersViewModel(api)
            vm.updateSearch("metro")
            advanceTimeBy(500)
            advanceUntilIdle()

            vm.setProvisionalOnly(true)
            advanceTimeBy(1)
            advanceUntilIdle()

            assertEquals("metro", api.lastSearch)
            assertTrue(api.listCalls.last().first, "and the chip itself still reached the request")
        }

    /**
     * Every header maps back to a sort, and to the *right* one.
     *
     * The indices are a positional contract with the column list in `SuppliersScreen`; getting one
     * wrong would silently sort by a neighbouring column, which looks like a server bug.
     */
    @Test
    fun `all six columns map back from their header index`() =
        runTest(dispatcher) {
            assertEquals(SupplierSortColumn.NAME, SupplierSortColumn.fromColumnIndex(0))
            assertEquals(SupplierSortColumn.VAT_ID, SupplierSortColumn.fromColumnIndex(1))
            assertEquals(SupplierSortColumn.CREDITOR_NO, SupplierSortColumn.fromColumnIndex(2))
            assertEquals(SupplierSortColumn.OPEN_TOTAL, SupplierSortColumn.fromColumnIndex(3))
            assertEquals(SupplierSortColumn.YTD_SPEND, SupplierSortColumn.fromColumnIndex(4))
            assertEquals(SupplierSortColumn.STATUS, SupplierSortColumn.fromColumnIndex(5))
            assertEquals(null, SupplierSortColumn.fromColumnIndex(6), "there is no seventh column")
        }

    @Test
    fun `an aggregate column sends its own wire value, not a neighbour's`() =
        runTest(dispatcher) {
            val api = FakeSupplierApiClient()
            val vm = SuppliersViewModel(api)

            vm.sortBy(SupplierSortColumn.OPEN_TOTAL)
            advanceTimeBy(1)
            advanceUntilIdle()
            assertEquals("OPEN_TOTAL", api.lastSort)

            vm.sortBy(SupplierSortColumn.YTD_SPEND)
            advanceTimeBy(1)
            advanceUntilIdle()
            assertEquals("YTD_SPEND", api.lastSort)
        }
}
