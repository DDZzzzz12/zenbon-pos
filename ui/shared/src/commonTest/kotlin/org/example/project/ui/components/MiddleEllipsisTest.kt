package org.example.project.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** NEW-05: long generated names keep their distinguishing end. One character = one width unit here. */
class MiddleEllipsisTest {
    private val byLength: (String) -> Int = { it.length }

    @Test
    fun aNameThatFitsIsUnchanged() {
        assertEquals("Rechnung.pdf", middleEllipsize("Rechnung.pdf", 20, byLength))
    }

    @Test
    fun aLongNameKeepsBothEnds() {
        val shown = middleEllipsize("Ausgangsrechnung_RG-2026-00006.pdf", 15, byLength)
        assertEquals(15, shown.length)
        assertTrue(shown.startsWith("Ausgang"), shown)
        assertTrue(shown.endsWith("06.pdf"), shown)
        assertTrue(MIDDLE_ELLIPSIS in shown, shown)
    }

    @Test
    fun twoNamesWithAnEqualStartStayDistinguishable() {
        val a = middleEllipsize("Rechnung_2026-10-16_Lieferant_A_0001.pdf", 20, byLength)
        val b = middleEllipsize("Rechnung_2026-10-16_Lieferant_A_0002.pdf", 20, byLength)
        assertTrue(a != b, "$a vs $b")
    }

    @Test
    fun noRoomAtAllLeavesTheEllipsis() {
        assertEquals(MIDDLE_ELLIPSIS, middleEllipsize("Rechnung.pdf", 1, byLength))
    }
}
