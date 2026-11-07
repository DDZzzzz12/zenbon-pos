package org.example.project.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class PosCardLayoutTest {
    @Test
    fun explicitRolesAreHonoured() {
        val layout =
            cardLayoutFor(listOf(PosCardRole.Title, PosCardRole.Detail, PosCardRole.Trailing, PosCardRole.Detail))
        assertEquals(PosCardLayout(titleIndex = 0, trailingIndex = 2, detailIndices = listOf(1, 3)), layout)
    }

    @Test
    fun noTitleFallsBackToTheFirstColumn() {
        val layout = cardLayoutFor(listOf(PosCardRole.Detail, PosCardRole.Detail))
        assertEquals(PosCardLayout(titleIndex = 0, trailingIndex = null, detailIndices = listOf(1)), layout)
    }

    @Test
    fun aSecondTitleOrTrailingBecomesADetailNothingIsDropped() {
        val layout =
            cardLayoutFor(listOf(PosCardRole.Title, PosCardRole.Title, PosCardRole.Trailing, PosCardRole.Trailing))
        assertEquals(PosCardLayout(titleIndex = 0, trailingIndex = 2, detailIndices = listOf(1, 3)), layout)
    }

    @Test
    fun titleCanBeAnyColumn() {
        val layout = cardLayoutFor(listOf(PosCardRole.Detail, PosCardRole.Detail, PosCardRole.Title))
        assertEquals(PosCardLayout(titleIndex = 2, trailingIndex = null, detailIndices = listOf(0, 1)), layout)
    }

    @Test
    fun emptyColumnsGiveAnEmptyLayout() {
        assertEquals(PosCardLayout(null, null, emptyList()), cardLayoutFor(emptyList()))
    }

    /** NEW-06: short facts share one line instead of a line each; none is dropped. */
    @Test
    fun metaColumnsShareTheMetaLine() {
        val layout =
            cardLayoutFor(listOf(PosCardRole.Title, PosCardRole.Meta, PosCardRole.Detail, PosCardRole.Meta, PosCardRole.Trailing))
        assertEquals(
            PosCardLayout(titleIndex = 0, trailingIndex = 4, detailIndices = listOf(2), metaIndices = listOf(1, 3)),
            layout,
        )
    }
}
