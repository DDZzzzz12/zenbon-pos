package org.example.project.ui.components

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PosDialogFooterLayoutTest {
    @Test
    fun shortLabelsFitSideBySide() {
        assertFalse(posDialogFooterStacks(335.dp, "Speichern", "Abbrechen"))
    }

    @Test
    fun aLongLabelStacksOnANarrowDialog() {
        assertTrue(posDialogFooterStacks(310.dp, "Alle Geräte jetzt abmelden", "Abbrechen"))
    }

    @Test
    fun neverStacksOnAWideDialog() {
        assertFalse(posDialogFooterStacks(900.dp, "Alle Geräte jetzt abmelden", "Abbrechen"))
    }

    @Test
    fun closeTargetIs48OnAPhoneAnd32Elsewhere() {
        assertEquals(48.dp, posDialogCloseTargetSize(390.dp))
        assertEquals(48.dp, posDialogCloseTargetSize(599.dp))
        assertEquals(32.dp, posDialogCloseTargetSize(600.dp))
        assertEquals(32.dp, posDialogCloseTargetSize(1024.dp))
    }
}
