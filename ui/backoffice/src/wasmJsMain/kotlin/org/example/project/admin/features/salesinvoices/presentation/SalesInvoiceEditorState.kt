package org.example.project.admin.features.salesinvoices.presentation

import org.example.project.core.domain.dto.debtor.DebtorDto
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.utils.toCanonicalMoneyOrNull
import org.example.project.money.Decimal

/**
 * The standalone-invoice editor's state. [lines] are the gross-per-line inputs; the server derives
 * net/VAT and sums the breakdown, so the client never computes a total it trusts — [subtotal] is
 * display feedback only.
 *
 * [status] is the wire status of the loaded invoice (null on the create form); every mutating action
 * is gated on `DRAFT`, and the server enforces the same rule with a 409.
 */
data class SalesInvoiceEditorState(
    val invoiceId: Long? = null,
    val status: String? = null,
    val invoiceNumber: String = "",
    val debtorId: Long? = null,
    val debtors: List<DebtorDto.ResponseDto> = emptyList(),
    val buyerName: String = "",
    val buyerVatId: String = "",
    val buyerStreet: String = "",
    val buyerZip: String = "",
    val buyerCity: String = "",
    val buyerCountry: String = "",
    val buyerEmail: String = "",
    val serviceDate: String = "", // ISO-8601 local date-time
    val dueDate: String = "",
    val paymentTermsText: String = "",
    val revenueAccountNo: String = "",
    val buCode: String = "",
    val notes: String = "",
    val currency: String = "EUR",
    val lines: List<EditorLine> = listOf(EditorLine()),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val hasChanges: Boolean = false,
    val saved: Boolean = false,
    val isIssued: Boolean = false,
    val error: UiError? = null,
    /** What the last Save press flagged, keyed by [SalesInvoiceEditorFields]; transient. */
    val fieldErrors: Map<String, StringKey> = emptyMap(),
    /** Bumped on every Save press that found errors, so the screen scrolls to the first one each time. */
    val validationAttempt: Int = 0,
) {
    val buyerNameError: StringKey? get() = fieldErrors[SalesInvoiceEditorFields.BUYER_NAME]

    val serviceDateError: StringKey? get() = fieldErrors[SalesInvoiceEditorFields.SERVICE_DATE]

    val dueDateError: StringKey? get() = fieldErrors[SalesInvoiceEditorFields.DUE_DATE]

    /** The first line error on screen, if any: what used to be the editor's single line verdict. */
    val lineError: StringKey?
        get() = SalesInvoiceEditorFields.order(lines.size).drop(3).firstNotNullOfOrNull { fieldErrors[it] }

    /**
     * The create form is savable as it opens (validate on press, UI-E2-06: pressing Save is how the
     * operator learns what is missing); a loaded draft only once something changed.
     *
     * Saving is creating (no id) or replacing a draft (id + DRAFT). Never anything else — and never a
     * draft whose debtor was deactivated: it can no longer be issued, and the server refuses the edit
     * with 409 `DEBTOR_INACTIVE` as well (project rule 8).
     */
    val canSave: Boolean
        get() = !isSaving && (invoiceId == null || (hasChanges && status == "DRAFT" && !debtorInactive))

    /**
     * The single read-only flag of the editor: a loaded invoice (an id) that is no longer a draft —
     * issued or cancelled, so the form is a view, not an editor. Every field, the debtor picker and the line
     * controls are disabled and the view model ignores edits; the server refuses them with 409
     * `SALES_INVOICE_IMMUTABLE` regardless (project rule 8).
     */
    val readOnly: Boolean
        get() = invoiceId != null && status != null && status != "DRAFT"

    /** Issuing consumes the §14 number; only a loaded draft may do it (the server 409s otherwise). */
    val canIssue: Boolean
        get() = invoiceId != null && status == "DRAFT" && !isSaving && !debtorInactive

    /**
     * "Speichern und ausstellen": a new invoice or a loaded draft whose debtor is still active — the
     * save is part of the action, so unlike [canIssue] it needs no id yet. Never an issued or cancelled
     * invoice (the server 409s those too).
     */
    val canSaveAndIssue: Boolean
        get() = !isSaving && !readOnly && !debtorInactive

    /**
     * The draft names a debtor that is not among the loaded (active-only) debtors, i.e. it was
     * deactivated after the draft was written. Issuing and saving are withheld; the server refuses
     * both with 409 `DEBTOR_INACTIVE` regardless (project rule 8).
     */
    val debtorInactive: Boolean
        get() = debtorId != null && !isLoading && debtors.none { it.id == debtorId }

    /** The running total, summed in [Decimal] for the totals row. Unparseable lines are skipped. */
    val subtotal: Decimal
        get() =
            lines.fold(Decimal.ZERO) { total, line ->
                val quantity = Decimal.parseOrNull(line.quantity.trim()) ?: return@fold total
                val price = line.grossUnitPrice.toCanonicalMoneyOrNull()?.let(Decimal::parseOrNull) ?: return@fold total
                total + quantity * price
            }
}
