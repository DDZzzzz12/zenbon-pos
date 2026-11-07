package org.example.project.admin.features.kassenbuch.presentation

import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.utils.KassenbuchBelegDateRule

/**
 * UI state of the book-management dialog: the shop's cash books, the one being created or edited,
 * and the automatic-Geldtransit target.
 *
 * [transferTargetId] `null` means the automatic transfer is **off** — a real configuration, not an
 * absent one. [isTransferTargetLoaded] is what keeps that distinct from "the server has not been
 * asked yet"; without it the picker would render "off" while still loading and a save issued in
 * that moment would write an off the operator never chose.
 */
data class BookManagementState(
    val books: List<CashBookDto.ResponseDto> = emptyList(),
    /** The open create/edit form, or `null` when the dialog is showing the list. */
    val form: CashBookFormState? = null,
    /** [form] as it opened: the pristine baseline for [hasUnsavedForm]. */
    val formOriginal: CashBookFormState? = null,
    val transferTargetId: Long? = null,
    val savedTransferTargetId: Long? = null,
    val isTransferTargetLoaded: Boolean = false,
    val isSavingTransferTarget: Boolean = false,
    /** The Kassenbuch Beleg-age limit (days) as typed. */
    val belegAgeInput: String = "",
    /** The stored limit; `null` until it has been read, so a save cannot fire before the load. */
    val savedBelegAgeDays: Int? = null,
    val isSavingBelegAge: Boolean = false,
    /** The signing-till picker of the book currently open in the edit form. */
    val signingTerminal: SigningTerminalPicker = SigningTerminalPicker(),
    /** The book [signingTerminal] belongs to. */
    val signingBookId: Long? = null,
    /**
     * True until the first answer arrives, so the dialog opens on a loading state rather than on an
     * empty list that reads as "no books".
     */
    val isLoading: Boolean = true,
    /** Whether the books have been read at least once since the dialog opened; false after a failed first load. */
    val isLoaded: Boolean = false,
    val error: UiError? = null,
    /**
     * A one-shot success confirmation for a save that persists on its own (the signing till), as a
     * key because `str()` is `@Composable`; the host's `PosSuccessWatcher` resolves it and clears it.
     */
    val successMessage: StringKey? = null,
) {
    /**
     * The books offerable as the transfer destination: active and non-fiscal.
     *
     * The fiscal Ladenkasse is excluded because it is the *source* — every Z-Bon moves the day's
     * takings out of it, so naming it would be a transit onto itself, and the server refuses it.
     * A deactivated book is excluded because the takings would land in a ledger daily operation no
     * longer shows.
     */
    val transferTargetCandidates: List<CashBookDto.ResponseDto>
        get() = books.filter { !it.isFiscal && it.isActive }.sortedWith(compareBy({ it.sortOrder }, { it.name }))

    /** The chosen target's name, or `null` when the transfer is off or the book is gone. */
    val transferTargetName: String?
        get() = transferTargetId?.let { id -> books.firstOrNull { it.id == id }?.name }

    val hasUnsavedTransferTarget: Boolean
        get() = isTransferTargetLoaded && transferTargetId != savedTransferTargetId

    /** The open book form differs from what it opened with, or its signing-till pick is unsaved. */
    val hasUnsavedForm: Boolean
        get() {
            val current = form ?: return false
            val original = formOriginal
            return (original != null && current.isDirtyFrom(original)) || signingTerminal.hasUnsavedChange
        }

    /** What closing the dialog from its current face would lose, saved separately from this dialog's own saves. */
    val isDirty: Boolean
        get() = if (form != null) hasUnsavedForm else hasUnsavedTransferTarget || hasUnsavedBelegAge

    /** The typed Beleg-age limit when it is a whole number the server accepts, else `null`. */
    val parsedBelegAgeDays: Int?
        get() = belegAgeInput.trim().toIntOrNull()?.takeIf { it in KassenbuchBelegDateRule.ALLOWED_MAX_AGE_DAYS }

    /** True only for something typed that the server would refuse, so an untouched field stays quiet. */
    val belegAgeInputInvalid: Boolean
        get() = belegAgeInput.isNotBlank() && parsedBelegAgeDays == null

    val hasUnsavedBelegAge: Boolean
        get() = savedBelegAgeDays != null && parsedBelegAgeDays != null && parsedBelegAgeDays != savedBelegAgeDays

    /** Books in the order the list renders them: active first, then by sort order and name. */
    val listedBooks: List<CashBookDto.ResponseDto>
        get() = books.sortedWith(compareByDescending<CashBookDto.ResponseDto> { it.isActive }.thenBy { it.sortOrder }.thenBy { it.name })
}
