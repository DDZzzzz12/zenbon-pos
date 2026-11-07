package org.example.project.admin.features.settings.presentation.sections

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VatClassPickerLayoutTest {
    @Test
    fun aNarrowDialogUsesTheDropdownSoEveryOptionIsReachable() {
        assertTrue(usesPickerField(236.dp)) // measured: the room a 390dp phone leaves the strip
        assertTrue(usesPickerField(479.dp))
    }

    @Test
    fun aWideDialogKeepsTheSegmentedStrip() {
        assertFalse(usesPickerField(480.dp))
        assertFalse(usesPickerField(570.dp)) // the 620dp-max dialog's content width
    }
}
