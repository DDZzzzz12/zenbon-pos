package org.example.project.admin.features.voucher

import org.example.project.admin.features.voucher.presentation.VoucherFilterSummary
import kotlin.test.Test
import kotlin.test.assertEquals

class VoucherFilterSummaryTest {
    @Test
    fun employeeCountsWhenThePickerIsOffered() {
        assertEquals(1, VoucherFilterSummary.activeCount("e-1", canPickEmployee = true, searchTerm = "", searchInPanel = false))
    }

    @Test
    fun employeeDoesNotCountWithoutThePicker() {
        assertEquals(0, VoucherFilterSummary.activeCount("e-1", canPickEmployee = false, searchTerm = "", searchInPanel = false))
    }

    @Test
    fun searchCountsWhenItLivesInThePanel() {
        assertEquals(1, VoucherFilterSummary.activeCount(null, canPickEmployee = true, searchTerm = "G2026", searchInPanel = true))
    }

    @Test
    fun searchDoesNotCountWhenItIsInTheBar() {
        assertEquals(0, VoucherFilterSummary.activeCount(null, canPickEmployee = true, searchTerm = "G2026", searchInPanel = false))
    }

    @Test
    fun employeeAndPanelSearchCountTwo() {
        assertEquals(2, VoucherFilterSummary.activeCount("e-1", canPickEmployee = true, searchTerm = "G2026", searchInPanel = true))
    }

    @Test
    fun nothingSetCountsZero() {
        assertEquals(0, VoucherFilterSummary.activeCount(null, canPickEmployee = true, searchTerm = "  ", searchInPanel = true))
    }
}
