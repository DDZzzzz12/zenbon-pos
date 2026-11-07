package org.example.project.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class PosFormGridLayoutTest {
    @Test
    fun aPhoneGetsOneColumn() {
        assertEquals(1, PosFormGridLayout.columnsFor(326.dp))
        assertEquals(1, PosFormGridLayout.columnsFor(491.dp))
    }

    @Test
    fun twoCellsNeedTwoMinimumsPlusTheGap() {
        // 2 * 240 + 12
        assertEquals(2, PosFormGridLayout.columnsFor(492.dp))
        assertEquals(2, PosFormGridLayout.columnsFor(743.dp))
        assertEquals(3, PosFormGridLayout.columnsFor(744.dp))
        assertEquals(3, PosFormGridLayout.columnsFor(2000.dp))
    }

    @Test
    fun maxColumnsCapsTheCount() {
        assertEquals(2, PosFormGridLayout.columnsFor(2000.dp, maxColumns = 2))
        assertEquals(1, PosFormGridLayout.columnsFor(2000.dp, maxColumns = 1))
    }

    @Test
    fun anUnboundedWidthFallsBackToOneColumn() {
        assertEquals(1, PosFormGridLayout.columnsFor(Dp.Infinity))
    }

    @Test
    fun cellWidthSharesTheLineAndNeverOverflowsIt() {
        assertEquals(390.dp, PosFormGridLayout.cellWidth(390.dp, 1))
        assertEquals(240.dp, PosFormGridLayout.cellWidth(492.dp, 2))
        // a fractional share is floored so the cells can never sum past the line and wrap early
        assertEquals(244.dp, PosFormGridLayout.cellWidth(500.9.dp, 2))
        assertEquals(240.dp, PosFormGridLayout.cellWidth(744.dp, 3))
    }
}
