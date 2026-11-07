package org.example.project.admin.features.kassenbuch

import org.example.project.admin.features.kassenbuch.presentation.EntryFormFields
import org.example.project.admin.features.kassenbuch.presentation.EntryLineInput
import org.example.project.admin.features.kassenbuch.presentation.KassenbuchEntryFormState
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * QA finding NF-4 (rt49): the back-office form asked for a USt-Satz on a Geldtransit, which carries
 * no VAT at all (`KassenbuchEntryType.carriesVatRate`). The typed rate was silently discarded by the
 * server. The till form already hides the control and submits rate 0; this pins the same behaviour.
 */
class EntryFormVatRateTest {
    private fun form(
        type: KassenbuchEntryType,
        vatRate: String,
    ) = KassenbuchEntryFormState(
        cashBookId = 12L,
        cashBookName = "Tresor",
        entryType = type,
        belegtext = "Transit",
        lines = listOf(EntryLineInput(amount = "2,00", vatRate = vatRate)),
        documentId = 501L,
        availableCash = "100.00",
    )

    @Test
    fun `a type without VAT is submittable with no rate typed`() {
        val state = form(KassenbuchEntryType.CASH_TRANSFER_OUT, vatRate = "")

        assertTrue(state.canSubmit)
        assertFalse(state.hasMissingVatRate)
        assertNull(state.fieldErrors()[EntryFormFields.vatRate(0)])
    }

    @Test
    fun `a type without VAT submits rate 0 whatever was typed`() {
        val state = form(KassenbuchEntryType.CASH_TRANSFER_OUT, vatRate = "19")

        assertEquals(
            "0",
            state
                .toRequest()
                .lines
                .single()
                .vatRate,
        )
    }

    @Test
    fun `a type with VAT still needs its rate`() {
        val state = form(KassenbuchEntryType.CASH_EXPENSE, vatRate = "")

        assertFalse(state.canSubmit)
        assertTrue(state.hasMissingVatRate)
        assertTrue(EntryFormFields.vatRate(0) in state.fieldErrors())
        assertEquals(
            "19",
            form(KassenbuchEntryType.CASH_EXPENSE, vatRate = "19")
                .toRequest()
                .lines
                .single()
                .vatRate,
        )
    }
}
