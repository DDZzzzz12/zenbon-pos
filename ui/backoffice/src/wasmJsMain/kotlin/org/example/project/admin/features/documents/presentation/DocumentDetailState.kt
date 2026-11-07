package org.example.project.admin.features.documents.presentation

import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey

/**
 * State for the document detail screen: the loaded document and metadata, the file bytes of a
 * non-PDF file, plus the editable [form] and its [pristine] snapshot at load/save time.
 *
 * [fileBytes] is null for a PDF: the shared `PdfViewer` streams it from the document's URL, and the
 * actions that need its bytes fetch them on press ([DocumentDetailViewModel.withFileBytes]). Only a
 * file type with no in-page preview has its bytes loaded up front, for the header's download.
 *
 * Holds [fileBytes] as a `ByteArray`, which breaks `data class` structural equality for the whole
 * state. That is fine here — only [isDirty] compares anything, and it compares [form] to [pristine]
 * (both plain `String`-only data classes), never the whole [DocumentDetailState]. Do not add an
 * `equals` override and do not compare two [DocumentDetailState] instances directly.
 */
data class DocumentDetailState(
    val isLoading: Boolean = true,
    /**
     * Bumped by every [DocumentDetailViewModel.load], including the error dialog's Retry. The PDF pane keys its
     * viewer source on it, so a retry reopens the PDF too — an equal document alone would not.
     */
    val loadEpoch: Int = 0,
    val isSaving: Boolean = false,
    val isDeleting: Boolean = false,
    val document: DocumentDto.DocumentResponseDto? = null,
    val metadata: DocumentDto.DocumentMetadataDto? = null,
    val fileBytes: ByteArray? = null,
    val form: DocumentMetadataForm = DocumentMetadataForm(),
    val pristine: DocumentMetadataForm = DocumentMetadataForm(),
    val showDeleteDialog: Boolean = false,
    /** The Storno dialog (T9-19) and its reason input; see [canCancelInvoice]. */
    val showCancelDialog: Boolean = false,
    val cancelReason: String = "",
    val isCancelling: Boolean = false,
    val error: UiError? = null,
    /** Field errors of the last refused Save; transient, never part of [isDirty]. */
    val fieldErrors: Map<DocumentMetadataField, StringKey> = emptyMap(),
    /** Bumped on every refused Save, so the screen scrolls to (and focuses) the first error each time. */
    val validationAttempt: Int = 0,
    /**
     * Held as a [StringKey] rather than a resolved `String`, because adminApp state is built outside
     * a composition and `str()` is `@Composable`; the screen resolves it. Consumed by
     * `PosSuccessWatcher`, which clears it after raising the toast. Set only when a metadata write
     * actually landed — the types with no metadata endpoint save nothing and stay silent.
     */
    val successMessage: StringKey? = null,
) {
    val isDirty: Boolean get() = form != pristine

    /**
     * T9-19: a document an invoice was read from or captured against is never deleted — the server
     * refuses with `DELETE_HAS_REFERENCES`. The header offers *Stornieren* in its place.
     */
    val deleteBlockedByInvoice: Boolean get() = document?.invoiceId != null

    /** The invoice has been cancelled by a correction; the Beleg shows the Storno notice. */
    val invoiceStorno: DocumentDto.InvoiceStornoDto? get() = document?.invoiceStorno

    /**
     * A Beleg with a live invoice can be cancelled by a correction — once. The server refuses a
     * second correction and an allocated or paid invoice with `INVOICE_NOT_CANCELLABLE`.
     */
    val canCancelInvoice: Boolean get() = document?.invoiceId != null && invoiceStorno == null && !isCancelling

    /** Mirrors `CancelInvoiceRequestDto.validate()`, so the confirm button cannot send a refused body. */
    val cancelReasonTooLong: Boolean
        get() = cancelReason.trim().length > DocumentDto.CancelInvoiceRequestDto.MAX_REASON_LENGTH

    val cancelReasonValid: Boolean get() = cancelReason.isNotBlank() && !cancelReasonTooLong
}
