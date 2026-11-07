package org.example.project.admin.core.presentation.theme

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.example.project.ui.components.fitHeaderFontSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** QA admin19 NOTE-c: table headings split mid-word ("Rechnungsnumm/er"); the fit shrinks instead. */
class HeaderFontFitTest {
    @Test
    fun aWordThatFits_keepsTheBaseSize() {
        val fit = fitHeaderFontSize(14.sp, wordWidthPx = 80, availablePx = 100, min = 10.sp)
        assertEquals(14.sp, fit.fontSize)
        assertTrue(fit.fitsAWord)
    }

    @Test
    fun aWordTooWide_shrinksToFit() {
        val fit = fitHeaderFontSize(14.sp, wordWidthPx = 120, availablePx = 100, min = 10.sp)
        assertTrue(fit.fontSize.value < 14f * 100 / 120 + 0.001f)
        assertTrue(fit.fontSize.value >= 10f)
        assertTrue(fit.fitsAWord)
    }

    @Test
    fun belowTheFloor_ellipsizesInsteadOfSplitting() {
        val fit = fitHeaderFontSize(14.sp, wordWidthPx = 300, availablePx = 100, min = 10.sp)
        assertEquals(10.sp, fit.fontSize)
        assertFalse(fit.fitsAWord)
    }

    @Test
    fun unboundedOrUnspecified_isLeftAlone() {
        assertEquals(14.sp, fitHeaderFontSize(14.sp, 300, Int.MAX_VALUE, 10.sp).fontSize)
        assertEquals(TextUnit.Unspecified, fitHeaderFontSize(TextUnit.Unspecified, 300, 100, 10.sp).fontSize)
    }
}
