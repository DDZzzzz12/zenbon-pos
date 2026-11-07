package org.example.project.admin.features.kassenbuch

import org.example.project.admin.features.kassenbuch.presentation.BookManagementState
import org.example.project.admin.features.kassenbuch.presentation.CashBookFormState
import org.example.project.admin.features.kassenbuch.presentation.EntryLineInput
import org.example.project.admin.features.kassenbuch.presentation.KassenbuchEntryFormState
import org.example.project.admin.features.kassenbuch.presentation.KassensturzUiState
import org.example.project.admin.features.kassenbuch.presentation.SigningTerminalPicker
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.enums.kassenbuch.CashBookCloseInterval
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins when a Kassenbuch dialog counts as holding unsaved edits: pristine comparison, never "was touched". */
class FinanceDialogDirtyTest {
    private val opened = KassenbuchEntryFormState(cashBookId = 1L, cashBookName = "Tresor", belegDatum = "13.10.2026")

    @Test
    fun `a freshly opened entry form is clean`() {
        assertFalse(opened.isDirtyFrom(opened))
        assertFalse(opened.copy(isSubmitting = true, availableCash = "5.00").isDirtyFrom(opened))
    }

    @Test
    fun `typing into the entry form makes it dirty and undoing makes it clean again`() {
        val typed = opened.copy(belegtext = "Porto")
        assertTrue(typed.isDirtyFrom(opened))
        assertFalse(typed.copy(belegtext = "").isDirtyFrom(opened))
    }

    @Test
    fun `entry type, lines and document each count as an edit`() {
        assertTrue(opened.copy(entryType = KassenbuchEntryType.CASH_DEPOSIT).isDirtyFrom(opened))
        assertTrue(opened.copy(lines = listOf(EntryLineInput(amount = "1,00"))).isDirtyFrom(opened))
        assertTrue(opened.copy(documentId = 7L).isDirtyFrom(opened))
    }

    private fun sturz() =
        KassensturzUiState(
            preview =
                CashBookDto.ClosePreviewDto(
                    cashBookId = 1L,
                    cashBookName = "Tresor",
                    periodStart = "2026-10-01",
                    periodEnd = "2026-10-31",
                    bookBalance = "100.00",
                ),
        )

    @Test
    fun `kassensturz is dirty only once a count or note is typed`() {
        assertFalse(sturz().isDirty)
        assertFalse(sturz().copy(countedInput = "  ").isDirty)
        assertTrue(sturz().copy(countedInput = "0").isDirty)
        assertTrue(sturz().copy(notes = "x").isDirty)
    }

    private val book =
        CashBookDto.ResponseDto(
            id = 2L,
            name = "Tresor",
            isFiscal = false,
            datevAccount = "1600",
            closeInterval = CashBookCloseInterval.MANUAL,
            isActive = true,
            sortOrder = 0,
            currentBalance = "0",
            currency = "EUR",
        )

    @Test
    fun `book form is dirty only when it differs from its baseline`() {
        val original = CashBookFormState.of(book)
        val state = BookManagementState(form = original, formOriginal = original)
        assertFalse(state.hasUnsavedForm)
        assertFalse(state.isDirty)
        assertFalse(state.copy(form = original.copy(isSubmitting = true, serverRefusedName = "x")).isDirty)
        assertTrue(state.copy(form = original.copy(name = "Neu")).isDirty)
        assertFalse(state.copy(form = original.copy(name = "Neu").copy(name = "Tresor")).isDirty)
    }

    @Test
    fun `create form baseline is the empty form`() {
        val empty = CashBookFormState()
        val state = BookManagementState(form = empty, formOriginal = empty)
        assertFalse(state.isDirty)
        assertTrue(state.copy(form = empty.copy(datevAccount = "1")).isDirty)
    }

    @Test
    fun `an unsaved signing till pick keeps the form dirty`() {
        val original = CashBookFormState.of(book)
        val picker = SigningTerminalPicker(visible = true, selectedId = 3L, savedId = null)
        assertTrue(BookManagementState(form = original, formOriginal = original, signingTerminal = picker).isDirty)
    }

    @Test
    fun `list face is dirty for an unsaved transfer target or Beleg age`() {
        assertFalse(BookManagementState().isDirty)
        assertTrue(
            BookManagementState(isTransferTargetLoaded = true, transferTargetId = 2L, savedTransferTargetId = null).isDirty,
        )
        assertTrue(BookManagementState(savedBelegAgeDays = 30, belegAgeInput = "60").isDirty)
    }
}
