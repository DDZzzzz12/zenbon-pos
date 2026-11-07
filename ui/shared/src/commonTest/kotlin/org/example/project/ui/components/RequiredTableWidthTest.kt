package org.example.project.ui.components

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class RequiredTableWidthTest {
    @Test
    fun emptyIsZero() = assertEquals(0.dp, requiredTableWidth(emptyList()))

    @Test
    fun defaultColumnsCost104Each() {
        val cols = List(3) { PosTableColumn("c$it") }
        assertEquals(312.dp, requiredTableWidth(cols))
    }

    @Test
    fun perColumnOverrideIsHonoured() {
        val cols = listOf(PosTableColumn("a"), PosTableColumn("b", minWidth = PosTableMin.DateTime))
        // Equal weights: the wider column (136) must fit its half, so 2 * 136; the plain sum is 240.
        assertEquals(272.dp, requiredTableWidth(cols))
    }

    @Test
    fun orderDoesNotMatter() {
        val a = PosTableColumn("a", minWidth = PosTableMin.Id)
        val b = PosTableColumn("b", minWidth = PosTableMin.Pill)
        assertEquals(requiredTableWidth(listOf(a, b)), requiredTableWidth(listOf(b, a)))
    }

    @Test
    fun unevenWeightsScaleByTheLightestColumn() {
        val cols =
            listOf(
                PosTableColumn("a", weight = 1f, minWidth = 96.dp),
                PosTableColumn("b", weight = 3f, minWidth = 96.dp),
            )
        assertEquals(416.dp, requiredTableWidth(cols))
    }

    @Test
    fun heavyColumnWithLargeMinDominatesOnlyWhenItsShareIsShort() {
        val cols =
            listOf(
                PosTableColumn("a", weight = 1f, minWidth = 96.dp),
                PosTableColumn("b", weight = 1.5f, minWidth = 200.dp),
            )
        // b: (200 + 8) * 2.5 / 1.5 = 346.67; a: 104 * 2.5 = 260; plain sum = 312.
        assertEquals(346.6667f, requiredTableWidth(cols).value, 0.01f)
    }

    @Test
    fun nonPositiveWeightFallsBackToTheSum() {
        val cols = listOf(PosTableColumn("a", weight = 0f), PosTableColumn("b", weight = 1f))
        assertEquals(208.dp, requiredTableWidth(cols))
    }
}
