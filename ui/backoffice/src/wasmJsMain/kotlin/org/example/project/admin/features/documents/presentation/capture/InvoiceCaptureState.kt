package org.example.project.admin.features.documents.presentation.capture

import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.dto.supplier.SupplierDto
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey

/** The provenance value that makes the supplier-asserted content read-only on the server. */
internal const val EXTRACTION_SOURCE_EINVOICE_XML = "EINVOICE_XML"

/**
 * **Never compare two instances of this class.** [fileBytes] is a `ByteArray`, whose `equals` is
 * identity, so structural equality on the whole state is meaningless — the same trap
 * `DocumentDetailState` documents. Dirty-tracking compares [form] against [pristine] only.
 *
 * [fieldErrors] / [rowErrors] / [warnings] are recomputed by the pure validators. They stay empty
 * until the first save attempt ([validationShown]) so a half-typed field is not shouted at, and are
 * refreshed on every keystroke afterwards so a corrected field clears immediately.
 */
internal data class InvoiceCaptureState(
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val document: DocumentDto.DocumentResponseDto? = null,
    val invoice: DocumentDto.InvoiceDto? = null,
    val fileBytes: ByteArray? = null,
    /** Media type of the Beleg behind [fileBytes]; null when unknown (invoice-keyed path), which previews as a PDF. */
    val fileMediaType: String? = null,
    val mode: InvoiceCaptureMode = InvoiceCaptureMode.Create,
    val form: InvoiceCaptureForm = InvoiceCaptureForm(),
    val pristine: InvoiceCaptureForm = InvoiceCaptureForm(),
    val fieldErrors: Map<InvoiceCaptureField, StringKey> = emptyMap(),
    val rowErrors: Map<Int, StringKey> = emptyMap(),
    val warnings: List<StringKey> = emptyList(),
    val validationShown: Boolean = false,
    /** Bumped on every save/confirm the validators refused, so the screen reveals the first error each time. */
    val validationAttempt: Int = 0,
    val error: UiError? = null,
    /**
     * Held as a [StringKey] rather than composeApp's resolved `String`, because adminApp state is
     * built outside a composition and `str()` is `@Composable`; the screen resolves it. Consumed by
     * `PosSuccessWatcher`, which clears it after raising the toast.
     */
    val successMessage: StringKey? = null,
    /**
     * Creditors whose name, VAT id or creditor number matches what has been typed into
     * Lieferant / Firma, newest search wins.
     *
     * Suggestions rather than a closed list: a scan may name a supplier the catalogue has never seen,
     * and forcing a pick would stop the operator filing it at all.
     */
    val supplierSuggestions: List<SupplierDto.ResponseDto> = emptyList(),
    val isSearchingSuppliers: Boolean = false,
    /** True once a search has answered for the current name, so "no match" is a finding, not a gap. */
    val supplierSearchAnswered: Boolean = false,
    /** Kept out of [error]: a creation failure belongs in the dialog, not behind it as a toast. */
    val supplierCreateError: UiError? = null,
    val isCreatingSupplier: Boolean = false,
) {
    /**
     * The creditor this name already matches exactly, or null.
     *
     * Case- and whitespace-insensitive, which is as far as the client can honestly go: the server
     * matches on a *normalized* name that also strips legal forms, so "Metro" may still reach the
     * same creditor as "Metro AG". Claiming more here would mean porting `normalizeSupplierName`
     * into the client and keeping two copies of it in step.
     */
    val matchedSupplier: SupplierDto.ResponseDto?
        get() {
            val typed = form.sellerName.trim()
            if (typed.isBlank()) return null
            return supplierSuggestions.firstOrNull { it.name.trim().equals(typed, ignoreCase = true) }
        }

    val isDirty: Boolean get() = !form.sameAs(pristine)

    /** True once the aggregate is a supplier e-invoice: the server rejects a supplier-field write. */
    val supplierReadOnly: Boolean
        get() = (mode as? InvoiceCaptureMode.Edit)?.extractionSource == EXTRACTION_SOURCE_EINVOICE_XML

    /**
     * The Storno this invoice takes part in (T9-19) — as the cancelled original or as the correction.
     * Either half is frozen: the server refuses content and booking writes with
     * `INVOICE_CANCELLED_READ_ONLY`, so the whole form is disabled here.
     */
    val storno: DocumentDto.InvoiceStornoDto? get() = invoice?.storno

    val stornoReadOnly: Boolean get() = storno != null

    /**
     * Whether *Als geprüft markieren* may run (R6-1). The action saves first, so it is as frozen as
     * Speichern on either half of a Storno — and running it there would only paint validation errors
     * onto fields the operator cannot edit.
     */
    val canConfirmReviewed: Boolean get() = !isSaving && !stornoReadOnly

    /**
     * The Beleg behind what is on screen, from whichever side is known: the document the screen was
     * opened on, or the invoice's own `sourceDocumentId` on the invoice-keyed path (R6-2). A Storno
     * correction is always opened invoice-keyed yet belongs to the original's Beleg, so the entry
     * point alone cannot say whether there is a file to show. Null only for a bare XML / hand-entered
     * invoice.
     */
    val belegDocumentId: Long? get() = document?.id ?: invoice?.sourceDocumentId

    /** The supplier-asserted scope needs writing. Never true for a supplier e-invoice — see [supplierReadOnly]. */
    val contentDirty: Boolean get() = !form.contentEquals(pristine) && !supplierReadOnly && !stornoReadOnly

    /** Our own booking decision needs writing; it is editable even for a supplier e-invoice, not after a Storno. */
    val bookingDirty: Boolean get() = !form.bookingEquals(pristine) && !stornoReadOnly

    /**
     * Whether a save from here would actually put a request on the wire. `Create` always POSTs;
     * `Edit` writes only the dirty scopes, so an `Edit` with nothing dirty is a no-op — and a
     * confirmation for a request that was never issued is a lie.
     */
    val willIssueWrite: Boolean
        get() = mode is InvoiceCaptureMode.Create || contentDirty || bookingDirty
}
