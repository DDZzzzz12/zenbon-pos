package org.example.project.ui.components

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PosKpiGridMathTest {
    @Test
    fun phoneWidthGivesTwoColumns() {
        assertEquals(2, PosKpiGridMath.columnsFor(358.dp, 4))
        assertEquals(2, PosKpiGridMath.columnsFor(358.dp, 5))
    }

    @Test
    fun balancesRows() {
        assertEquals(2, PosKpiGridMath.columnsFor(520.dp, 4))
        assertEquals(3, PosKpiGridMath.columnsFor(520.dp, 5))
    }

    @Test
    fun wideFitsAllInOneRow() {
        assertEquals(5, PosKpiGridMath.columnsFor(1160.dp, 5))
    }

    @Test
    fun tinyWidthIsOneColumn() {
        assertEquals(1, PosKpiGridMath.columnsFor(100.dp, 4))
    }

    @Test
    fun emptyIsOneColumn() {
        assertEquals(1, PosKpiGridMath.columnsFor(500.dp, 0))
    }

    @Test
    fun negativeCountThrows() {
        assertFailsWith<IllegalArgumentException> { PosKpiGridMath.columnsFor(500.dp, -1) }
        Unit
    }
}
