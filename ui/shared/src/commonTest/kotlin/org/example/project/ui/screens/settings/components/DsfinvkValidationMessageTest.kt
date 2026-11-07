package org.example.project.ui.screens.settings.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DsfinvkValidationMessageTest {
    @Test
    fun `the signature warning is read back into its counts`() {
        val parsed = DsfinvkValidationMessage.parse("15 transactions are missing TSE signatures (5 bills, 2 orders, 5 cash book entries, 3 cancellations)")
        assertEquals(DsfinvkValidationMessage.MissingSignatures(15, 5, 2, 5, 3), parsed)
    }

    @Test
    fun `a breakdown with only some kinds leaves the others at zero`() {
        val parsed = DsfinvkValidationMessage.parse("1 transactions are missing TSE signatures (1 cash book entries)")
        assertEquals(DsfinvkValidationMessage.MissingSignatures(1, 0, 0, 1, 0), parsed)
    }

    @Test
    fun `the vat and no-closing sentences are recognised`() {
        assertEquals(DsfinvkValidationMessage.MissingVatDetails(4), DsfinvkValidationMessage.parse("4 bills are missing VAT breakdown details"))
        assertEquals(DsfinvkValidationMessage.NoZReports, DsfinvkValidationMessage.parse("No Z-reports found in the specified date range"))
    }

    @Test
    fun `the tax id sentence yields the closing numbers`() {
        val raw =
            "DSFinV-K export requires Steuernummer or USt-IdNr (§ 14 Abs. 4 Nr. 2 UStG) — neither is present for Z_NR 3, 4. " +
                "A Kassenabschluss carries the Stammdaten snapshot taken when it was closed; configuring the shop settings now fixes only closings taken afterwards"
        assertEquals(DsfinvkValidationMessage.MissingTaxId("3, 4"), DsfinvkValidationMessage.parse(raw))
    }

    @Test
    fun `anything else is left to show as the server sent it`() {
        assertNull(DsfinvkValidationMessage.parse("something new"))
    }
}
