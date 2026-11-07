package org.example.project.admin.features.debtors.presentation

import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey

/**
 * [pristine] is the form as last loaded or saved; [isDirty] is derived from it rather than tracked
 * separately, so an edit-and-undo correctly reports clean.
 *
 * A null [id] is the create form. [salesInvoiceCount] decides whether deleting is offered at all —
 * the UI half of the guard the service enforces regardless.
 */
data class DebtorDetailState(
    val id: Long? = null,
    val form: DebtorForm = DebtorForm(),
    val pristine: DebtorForm = DebtorForm(),
    val salesInvoiceCount: Long = 0,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isDeleted: Boolean = false,
    /**
     * Bumped once per successful save.
     *
     * An explicit counter rather than something a caller infers from [isSaving] going false: a save
     * that changes nothing observable — the same name typed again — still has to announce itself,
     * and a flag transition is easy to miss and impossible to assert on. The Debitoren pane keys a
     * `LaunchedEffect` on this to tell the list to re-read itself.
     */
    val savedRevision: Int = 0,
    /**
     * The id the create form just received from the server, until the screen has announced it and
     * left the create route (NEW-20). One-shot: [DebtorDetailViewModel.consumeCreated] clears it.
     * Null after an update, so only a POST navigates.
     */
    val createdDebtorId: Long? = null,
    /** Field errors of the last Save press; transient, never part of [isDirty]. */
    val fieldErrors: Map<DebtorFormField, StringKey> = emptyMap(),
    /** Bumped on every Save press that found errors, so the screen scrolls to the first one each time. */
    val validationAttempt: Int = 0,
    val error: UiError? = null,
) {
    val isDirty: Boolean get() = form != pristine

    /**
     * Save is pressable on the create form however empty it is — pressing it is how the operator finds
     * out what is missing (UI-E2-06) — and on an existing debtor only once something changed. Never
     * while a fetch or another save is in flight.
     */
    val canSave: Boolean get() = (id == null || isDirty) && !isSaving && !isLoading

    /** True when this state already holds unsaved edits for [debtorId]; a reload must not discard them. */
    fun holdsDraftFor(debtorId: Long): Boolean = id == debtorId && isDirty

    /** A debtor an invoice points at is kept for §147 AO and deactivated instead of removed. */
    val canDelete: Boolean get() = id != null && salesInvoiceCount == 0L
}
