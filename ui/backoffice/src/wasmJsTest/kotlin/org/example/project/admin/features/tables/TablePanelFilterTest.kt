package org.example.project.admin.features.tables

import org.example.project.admin.features.tables.presentation.TablePanelFilter
import org.example.project.admin.features.tables.presentation.TableViewModel
import org.example.project.features.settings.presentation.state.TableManagementState
import org.example.project.features.settings.presentation.state.TableSortColumn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TablePanelFilterTest {
    private val default = TablePanelFilter(locationTypeId = null, sortColumn = TableSortColumn.TABLE_NUMBER, sortAscending = true)

    @Test
    fun noneMatchesTheStateDefaults() {
        assertEquals(TablePanelFilter.of(TableManagementState()), TablePanelFilter.NONE)
        assertEquals(default, TablePanelFilter.NONE)
    }

    @Test
    fun nothingAppliedCountsZero() {
        assertEquals(0, default.activeCount(locationInPanel = true))
    }

    @Test
    fun aLocationCountsOnlyWhereThePanelHoldsIt() {
        val filter = default.copy(locationTypeId = 3L)
        assertEquals(1, filter.activeCount(locationInPanel = true))
        // On wider windows the location is a quick chip in the bar, not a panel filter.
        assertEquals(0, filter.activeCount(locationInPanel = false))
    }

    @Test
    fun aNonDefaultSortCountsOne() {
        assertEquals(1, default.copy(sortColumn = TableSortColumn.SEATS).activeCount(locationInPanel = false))
        assertEquals(1, default.copy(sortAscending = false).activeCount(locationInPanel = false))
    }

    @Test
    fun locationAndSortCountTwoOnAPhone() {
        val filter = TablePanelFilter(locationTypeId = 2L, sortColumn = TableSortColumn.STATUS, sortAscending = false)
        assertEquals(2, filter.activeCount(locationInPanel = true))
    }

    @Test
    fun clearedDropsLocationAndSort() {
        val filter = TablePanelFilter(locationTypeId = 2L, sortColumn = TableSortColumn.STATUS, sortAscending = false)
        assertEquals(default, filter.cleared())
    }

    @Test
    fun anotherColumnAscendingIsOneToggle() {
        val target = default.copy(sortColumn = TableSortColumn.SEATS)
        assertEquals(listOf(TableSortColumn.SEATS), target.sortTogglesFrom(default))
    }

    @Test
    fun anotherColumnDescendingIsTwoToggles() {
        val target = default.copy(sortColumn = TableSortColumn.SEATS, sortAscending = false)
        assertEquals(listOf(TableSortColumn.SEATS, TableSortColumn.SEATS), target.sortTogglesFrom(default))
    }

    @Test
    fun theSameColumnFlippedIsOneToggle() {
        val target = default.copy(sortAscending = false)
        assertEquals(listOf(TableSortColumn.TABLE_NUMBER), target.sortTogglesFrom(default))
    }

    @Test
    fun anUnchangedSortIsNoToggle() {
        assertTrue(default.sortTogglesFrom(default).isEmpty())
    }

    @Test
    fun applySendsOnlyWhatChanged() {
        val calls = mutableListOf<String>()
        val applied = default.copy(locationTypeId = 4L)
        applied
            .copy(sortColumn = TableSortColumn.LOCATION_TYPE)
            .applyTo(applied, onLocation = { calls += "location:$it" }, onSortToggle = { calls += "sort:$it" })
        assertEquals(listOf("sort:LOCATION_TYPE"), calls)
    }

    @Test
    fun applySendsAClearedLocationAsNull() {
        val calls = mutableListOf<String>()
        default.applyTo(default.copy(locationTypeId = 4L), onLocation = { calls += "location:$it" }, onSortToggle = { calls += "sort:$it" })
        assertEquals(listOf("location:null"), calls)
    }

    /** The toggles drive the real view model's setSort to exactly the drafted column and direction. */
    @Test
    fun applyThroughTheViewModelReachesEveryColumnAndDirection() {
        val model = TableViewModel(FakeRTableApiClient(emptyList()))
        TableSortColumn.entries.forEach { column ->
            listOf(true, false).forEach { ascending ->
                val applied = TablePanelFilter.of(model.state)
                val target = TablePanelFilter(locationTypeId = 7L, sortColumn = column, sortAscending = ascending)
                target.applyTo(applied, model::selectLocationFilter, model::setSort)
                assertEquals(target, TablePanelFilter.of(model.state))
            }
        }
    }

    @Test
    fun thePanelShowsTheLocationOnAPhoneOrOnceTheDraftChangedIt() {
        assertTrue(default.showsLocation(locationInPanel = true, applied = default))
        assertFalse(default.showsLocation(locationInPanel = false, applied = default))
        assertTrue(default.copy(locationTypeId = 1L).showsLocation(locationInPanel = false, applied = default))
    }
}
