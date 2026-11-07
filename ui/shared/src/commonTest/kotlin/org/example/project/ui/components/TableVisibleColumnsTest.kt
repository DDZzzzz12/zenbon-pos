package org.example.project.ui.components

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** NEW-06: a low-value column gives way before the whole table turns into cards. */
class TableVisibleColumnsTest {
    private fun col(
        minWidth: Int,
        hideBelow: Int = 0,
    ) = PosAdaptiveColumn(PosTableColumn("c", minWidth = minWidth.dp), hideInTableBelow = hideBelow.dp)

    private val columns = listOf(col(120), col(96, hideBelow = 600), col(104))

    @Test
    fun wideEnough_everyColumnIsShown() {
        assertEquals(listOf(0, 1, 2), visibleTableColumns(columns, 700.dp))
    }

    @Test
    fun belowTheThreshold_theOptionalColumnGivesWay() {
        assertEquals(listOf(0, 2), visibleTableColumns(columns, 599.dp))
    }

    @Test
    fun theTableDecisionCountsOnlyTheShownColumns() {
        // All three need 344dp, but below 600dp only two (128 + 112 = 240dp) are shown.
        assertTrue(showsAsTable(columns, 300.dp, cardBelow = 0.dp))
        assertFalse(showsAsTable(columns, 230.dp, cardBelow = 0.dp))
        assertFalse(showsAsTable(columns, 300.dp, cardBelow = 400.dp), "cardBelow stays a lower bound")
    }
}
