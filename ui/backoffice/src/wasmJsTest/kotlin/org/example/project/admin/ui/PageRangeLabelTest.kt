package org.example.project.admin.ui

import org.example.project.ui.components.table.pageRangeLabel
import kotlin.test.Test
import kotlin.test.assertEquals

class PageRangeLabelTest {
    @Test fun firstPageRange() {
        assertEquals("1-20 / 47", pageRangeLabel(currentPage = 0, pageSize = 20, currentPageItemCount = 20, totalFilteredCount = 47))
    }

    @Test fun lastPartialPage() {
        assertEquals("41-47 / 47", pageRangeLabel(currentPage = 2, pageSize = 20, currentPageItemCount = 7, totalFilteredCount = 47))
    }

    @Test fun emptyShowsZero() {
        assertEquals("0 / 0", pageRangeLabel(currentPage = 0, pageSize = 20, currentPageItemCount = 0, totalFilteredCount = 0))
    }
}
