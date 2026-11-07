package org.example.project.features.financialdocuments

import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.utils.toCanonicalMoneyOrNull
import org.example.project.features.financialdocuments.presentation.components.correctionAmountPrefill
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Kassenbuch correction dialog prefills its editable amount fields from the original entry.
 * They must show money at scale 2 — never the server's stored `DECIMAL(19,4)` form (checklist 12.9's
 * "money edit fields" item) — and the prefill must still be accepted by the field's own parser.
 */
class CorrectionAmountPrefillTest {
    @Test
    fun aStoredScaleFourAmountIsPrefilledAtScaleTwo() {
        // Pre-fix the line path copied toPlainString() of the parsed value: "35.8100".
        assertEquals("35.81", correctionAmountPrefill("35.8100", documentIncoming = true))
    }

    @Test
    fun anOutgoingEntryIsFlippedBackToDocumentSign() {
        // Stored cash-flow sign is negative for an expense; the operator typed it positive.
        assertEquals("12.50", correctionAmountPrefill("-12.5000", documentIncoming = false))
    }

    @Test
    fun aTwoDecimalAmountIsUnchanged() {
        assertEquals("120.00", correctionAmountPrefill("120.00", documentIncoming = true))
    }

    @Test
    fun thePrefillRoundTripsThroughTheFieldParser() {
        val prefill = correctionAmountPrefill("1234.5600", documentIncoming = true)
        assertEquals("1234.56", prefill.toCanonicalMoneyOrNull())
    }

    @Test
    fun anUnparseableAmountPrefillsZero() {
        assertEquals("0.00", correctionAmountPrefill("n/a", documentIncoming = true))
    }

    @Test
    fun inGermanThePrefillUsesTheDecimalComma() {
        assertEquals("12,50", correctionAmountPrefill("-12.5000", documentIncoming = false, language = Language.GERMAN))
    }

    @Test
    fun theGermanPrefillIsStillAcceptedByTheFieldParser() {
        val prefill = correctionAmountPrefill("1234.5600", documentIncoming = true, language = Language.GERMAN)
        assertEquals("1234.56", prefill.toCanonicalMoneyOrNull())
    }
}
