package org.example.project.ui.components.beleg

import kotlin.test.Test
import kotlin.test.assertEquals

class BoldMarkupTest {
    @Test
    fun segmentsBetweenMarkersAreBold() {
        assertEquals(
            listOf("Nächster Schritt: " to false, "5 Rechnungen prüfen" to true, ", danach " to false, "14 Zahlungen zuordnen" to true, "." to false),
            splitBoldMarkup("Nächster Schritt: **5 Rechnungen prüfen**, danach **14 Zahlungen zuordnen**."),
        )
    }

    @Test
    fun textWithoutMarkersIsOnePlainSegment() {
        assertEquals(listOf("Alles erledigt" to false), splitBoldMarkup("Alles erledigt"))
    }

    @Test
    fun anUnclosedMarkerLeavesTheRestPlain() {
        assertEquals(listOf("a " to false, "b" to false), splitBoldMarkup("a **b"))
        assertEquals(listOf("a " to false, "b" to true, " c " to false, "d" to false), splitBoldMarkup("a **b** c **d"))
    }

    @Test
    fun emptyInputYieldsNoSegments() {
        assertEquals(emptyList(), splitBoldMarkup(""))
    }
}
