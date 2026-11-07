package org.example.project.admin.features.terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.features.settings.FakeShopConfigApiClient
import org.example.project.admin.features.terminal.presentation.TerminalPanelFilter
import org.example.project.admin.features.terminal.presentation.TerminalsState
import org.example.project.admin.features.terminal.presentation.TerminalsState.TerminalFilter
import org.example.project.admin.features.terminal.presentation.TerminalsState.TerminalSort
import org.example.project.admin.features.terminal.presentation.TerminalsViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalPanelFilterTest {
    private val dispatcher = StandardTestDispatcher()
    private val default = TerminalPanelFilter(status = TerminalFilter.ALL, sort = TerminalSort.TERMINAL_ID, sortAscending = true)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun noneMatchesTheStateDefaults() {
        assertEquals(TerminalPanelFilter.of(TerminalsState.Data(all = emptyList())), TerminalPanelFilter.NONE)
        assertEquals(default, TerminalPanelFilter.NONE)
    }

    @Test
    fun nothingAppliedCountsZero() {
        assertEquals(0, default.activeCount(statusInPanel = true))
        assertEquals(0, default.activeCount(statusInPanel = false))
    }

    @Test
    fun aStatusCountsOnlyWhereThePanelHoldsIt() {
        val filter = default.copy(status = TerminalFilter.PAIRING_OPEN)
        assertEquals(1, filter.activeCount(statusInPanel = true))
        // On wider windows the status is a quick chip in the bar, not a panel filter.
        assertEquals(0, filter.activeCount(statusInPanel = false))
    }

    @Test
    fun aNonDefaultSortCountsOne() {
        assertEquals(1, default.copy(sort = TerminalSort.NAME).activeCount(statusInPanel = false))
        assertEquals(1, default.copy(sortAscending = false).activeCount(statusInPanel = false))
    }

    @Test
    fun statusAndSortCountTwoOnAPhone() {
        val filter = TerminalPanelFilter(TerminalFilter.INACTIVE, TerminalSort.LAST_SEEN, sortAscending = false)
        assertEquals(2, filter.activeCount(statusInPanel = true))
    }

    @Test
    fun clearedDropsStatusAndSort() {
        val filter = TerminalPanelFilter(TerminalFilter.ACTIVE, TerminalSort.ROLE, sortAscending = false)
        assertEquals(default, filter.cleared())
    }

    @Test
    fun anotherSortAscendingIsOneToggle() {
        assertEquals(listOf(TerminalSort.NAME), default.copy(sort = TerminalSort.NAME).sortTogglesFrom(default))
    }

    @Test
    fun anotherSortDescendingIsTwoToggles() {
        val target = default.copy(sort = TerminalSort.NAME, sortAscending = false)
        assertEquals(listOf(TerminalSort.NAME, TerminalSort.NAME), target.sortTogglesFrom(default))
    }

    @Test
    fun onlyTheDirectionIsOneToggle() {
        assertEquals(listOf(TerminalSort.TERMINAL_ID), default.copy(sortAscending = false).sortTogglesFrom(default))
    }

    @Test
    fun unchangedSortIsNoToggle() {
        assertEquals(emptyList(), default.sortTogglesFrom(default))
    }

    @Test
    fun applyToSendsOnlyWhatDiffers() {
        val filters = mutableListOf<TerminalFilter>()
        val sorts = mutableListOf<TerminalSort>()
        default.applyTo(default, filters::add, sorts::add)
        assertTrue(filters.isEmpty())
        assertTrue(sorts.isEmpty())

        default.copy(status = TerminalFilter.ACTIVE).applyTo(default, filters::add, sorts::add)
        assertEquals(listOf(TerminalFilter.ACTIVE), filters)
        assertTrue(sorts.isEmpty())
    }

    @Test
    fun showsStatusOnAPhoneOrWhenTheDraftChangedIt() {
        assertTrue(default.showsStatus(statusInPanel = true, applied = default))
        assertFalse(default.showsStatus(statusInPanel = false, applied = default))
        assertTrue(default.copy(status = TerminalFilter.INACTIVE).showsStatus(statusInPanel = false, applied = default))
    }

    /** Drives the real view model's sort rule (same column flips, another starts ascending) for every pair. */
    @Test
    fun togglesReachEveryTargetThroughTheViewModel() =
        runTest(dispatcher) {
            val api = FakeTerminalApiClient().apply { terminals = listOf(TerminalTestFixtures.summary(1)) }
            val vm = TerminalsViewModel(api, FakeTseAdminApiClient(), FakeShopConfigApiClient())
            vm.refresh()
            advanceUntilIdle()
            val data = { vm.state as TerminalsState.Data }
            for (fromSort in TerminalSort.entries) {
                for (fromAsc in listOf(true, false)) {
                    for (toSort in TerminalSort.entries) {
                        for (toAsc in listOf(true, false)) {
                            // Reach the start state through the view model itself.
                            TerminalPanelFilter.NONE
                                .copy(sort = fromSort, sortAscending = fromAsc)
                                .applyTo(TerminalPanelFilter.of(data()), onFilter = vm::onFilter, onSort = vm::onSort)
                            val applied = TerminalPanelFilter.of(data())
                            assertEquals(fromSort to fromAsc, applied.sort to applied.sortAscending)
                            applied
                                .copy(sort = toSort, sortAscending = toAsc)
                                .applyTo(applied, onFilter = vm::onFilter, onSort = vm::onSort)
                            assertEquals(toSort to toAsc, data().sort to data().sortAscending, "from $fromSort/$fromAsc to $toSort/$toAsc")
                        }
                    }
                }
            }
        }
}
