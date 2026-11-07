package org.example.project.admin.features.supplier.presentation

import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey

/**
 * [pristine] is the form as last loaded or saved; [isDirty] is derived from it rather than tracked
 * separately, so an edit-and-undo correctly reports clean.
 */
data class SupplierDetailState(
    val id: Long? = null,
    val form: SupplierForm = SupplierForm(),
    val pristine: SupplierForm = SupplierForm(),
    val knownIbans: List<String> = emptyList(),
    /** Server-computed spend aggregates, as decimal strings, formatted for display with [currency]. */
    val openTotal: String = "0",
    val ytdSpend: String = "0",
    val currency: String = "EUR",
    val isProvisional: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val nameError: StringKey? = null,
    /** A malformed e-mail, refused on Save by the shared `EmailFormat` (the server refuses it too). */
    val emailError: StringKey? = null,
    /** Bumped on every refused Save press, so the screen focuses the first marked field each time. */
    val validationAttempt: Int = 0,
    val error: UiError? = null,
) {
    val isDirty: Boolean get() = form != pristine

    /**
     * A provisional supplier may be saved unchanged: that PUT is how the operator confirms it, and
     * the server clears the provisional flag on it.
     */
    val canSave: Boolean get() = (isDirty || isProvisional) && !isSaving
}
