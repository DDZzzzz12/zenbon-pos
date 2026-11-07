package org.example.project.admin.features.finance

import org.example.project.admin.features.finance.presentation.buchungsbeginnError
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.utils.dateWireValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Save is pressable; pressing it with no or a bad date names the problem on the field (UI-E2-06). */
class BuchungsbeginnValidationTest {
    @Test
    fun `blank is required and unreadable text asks for the typing pattern`() {
        assertEquals(CommonKeys.fieldRequired, buchungsbeginnError(""))
        assertEquals(CommonKeys.dateInvalid, buchungsbeginnError(dateWireValue("31.02.2026")))
    }

    @Test
    fun `a date typed in the German display format reaches the dialog as ISO and is accepted`() {
        val wire = dateWireValue("01.08.2026")
        assertEquals("2026-08-01", wire)
        assertNull(buchungsbeginnError(wire))
    }
}
