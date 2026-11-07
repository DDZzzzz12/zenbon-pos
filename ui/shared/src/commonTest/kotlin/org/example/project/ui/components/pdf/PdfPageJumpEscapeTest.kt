package org.example.project.ui.components.pdf

import kotlin.test.Test
import kotlin.test.assertEquals

class PdfPageJumpEscapeTest {
    /** The browser may never deliver the KeyUp to the field, so the cancel has to happen on the KeyDown. */
    @Test
    fun escKeyDownCancelsTheEdit() {
        assertEquals(PdfPageJumpEscape.Cancel, PdfPageJumpEscape.of(isEscape = true, isKeyDown = true))
    }

    /** A KeyUp that does arrive is swallowed, never acted on a second time and never passed to app-level handling. */
    @Test
    fun escKeyUpIsSwallowedWithoutActing() {
        val action = PdfPageJumpEscape.of(isEscape = true, isKeyDown = false)
        assertEquals(PdfPageJumpEscape.Swallow, action)
        assertEquals(true, action.consumes)
    }

    @Test
    fun cancelConsumesTheKeyDown() {
        assertEquals(true, PdfPageJumpEscape.Cancel.consumes)
    }

    /** Enter, digits and every other key keep their normal path (Enter is the field's Go action). */
    @Test
    fun otherKeysPassThroughInBothPhases() {
        assertEquals(PdfPageJumpEscape.PassThrough, PdfPageJumpEscape.of(isEscape = false, isKeyDown = true))
        assertEquals(PdfPageJumpEscape.PassThrough, PdfPageJumpEscape.of(isEscape = false, isKeyDown = false))
        assertEquals(false, PdfPageJumpEscape.PassThrough.consumes)
    }

    /** A held Esc repeats KeyDown: each one cancels again, which is harmless because cancel is idempotent. */
    @Test
    fun repeatedCancelIsIdempotent() {
        val once = PdfPageJumpEdit.showing(2).typed("12").cancel(currentPage = 2)
        assertEquals(once, once.cancel(currentPage = 2))
    }
}
