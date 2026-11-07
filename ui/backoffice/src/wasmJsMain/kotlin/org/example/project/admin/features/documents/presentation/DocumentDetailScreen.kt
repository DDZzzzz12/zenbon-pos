package org.example.project.admin.features.documents.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Print
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.browser.window
import org.example.project.admin.core.presentation.layout.PdfFormPane
import org.example.project.admin.core.presentation.layout.PdfFormPaneHost
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.util.pdf.AdminPdfTokenProvider
import org.example.project.admin.core.util.resolveApiUrl
import org.example.project.admin.features.documents.presentation.components.BankStatementMetadataForm
import org.example.project.admin.features.documents.presentation.components.CancelInvoiceDialog
import org.example.project.admin.features.documents.presentation.components.DeleteDocumentDialog
import org.example.project.admin.features.documents.presentation.components.DocumentHeaderBar
import org.example.project.admin.features.documents.presentation.components.DocumentHistoryCard
import org.example.project.admin.features.documents.presentation.components.DocumentInfoCard
import org.example.project.admin.features.documents.presentation.components.DocumentNoPreviewPane
import org.example.project.admin.features.documents.presentation.components.InvoiceEntryCard
import org.example.project.admin.features.documents.presentation.components.InvoiceStornoNotice
import org.example.project.admin.features.documents.presentation.components.StatementTransactionTable
import org.example.project.admin.features.invoices.presentation.InvoicesAccess
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.enums.document.DocumentType
import org.example.project.core.network.HttpCall
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.FileSaver
import org.example.project.ui.auth.LocalAccessPolicy
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosLoadingOverlay
import org.example.project.ui.components.PosSuccessWatcher
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.pdf.PdfSource
import org.example.project.ui.components.pdf.PdfViewer
import org.example.project.ui.guard.rememberDirtySource
import org.example.project.ui.layout.PaneBreakpoints
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Belege detail — a single document's two-pane view: the document (left, [DocumentPreviewPane]) +
 * metadata editor column (right, [DocumentMetadataColumn]), switchable ("Beleg | Daten") below 840dp
 * content width.
 *
 * A PDF is shown by the shared `PdfViewer`, which paints into the Compose canvas like everything else:
 * dialogs, toasts and the phone drawer simply draw over it, and the panes may resize freely. The page
 * itself still does not scroll: both panes take the full height and scroll internally (the viewer its
 * pages, the metadata column its form).
 */
@Composable
fun DocumentDetailScreen(
    documentId: Long,
    onBack: () -> Unit,
    onCapture: (Long) -> Unit,
    onOpenInvoice: (Long) -> Unit,
    viewModel: DocumentDetailViewModel = koinViewModel(),
) {
    val state = viewModel.state
    val policy = LocalAccessPolicy.current
    val access = DocumentsAccess.of(policy)
    val mayCapture = InvoicesAccess.of(policy).mayCapture

    LaunchedEffect(documentId) { viewModel.load(documentId) }

    PosErrorWatcher(
        error = state.error,
        title = str(AdminStringKeys.DocumentDetailTitle),
        onClear = viewModel::clearError,
        retryAction = { viewModel.load(documentId) },
    )

    // A toast, not a dialog: the save already re-baselines `pristine`, which greys Speichern out, so
    // this only has to say that the greying meant success rather than a swallowed failure.
    PosSuccessWatcher(
        message = state.successMessage?.let { str(it) },
        title = str(AdminStringKeys.DocumentDetailTitle),
        onClear = viewModel::clearSuccessMessage,
    )

    // Leaving is guarded centrally (AdminNavHost back/go). Live read: a confirmed delete re-pins the form and
    // navigates away in the same event, before any recomposition, and must not ask about a document that is gone.
    rememberDirtySource(dirty = { viewModel.state.isDirty }, onDiscard = viewModel::discardDraft)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < PaneBreakpoints.PHONE_MAX
        Column(Modifier.fillMaxSize().padding(if (compact) 8.dp else 16.dp)) {
            DocumentHeaderBar(
                filename = state.document?.filename.orEmpty(),
                fileType = state.document?.fileType.orEmpty(),
                canSave = access.mayWrite,
                canDelete = access.mayDelete,
                mayCancelInvoice = access.mayCancelInvoice,
                hasInvoice = state.deleteBlockedByInvoice,
                canCancelInvoice = state.canCancelInvoice,
                isDirty = state.isDirty,
                isSaving = state.isSaving,
                onBack = onBack,
                onDownload = {
                    val document = state.document ?: return@DocumentHeaderBar
                    viewModel.withFileBytes(documentId, onFailed = {}) { bytes ->
                        FileSaver.saveFile(data = bytes, filename = document.filename, mimeType = document.mediaType)
                    }
                },
                onDelete = viewModel::requestDelete,
                onCancelInvoice = viewModel::requestCancelInvoice,
                onSave = { viewModel.save(documentId) },
                compact = compact,
            )
            Spacer(Modifier.height(12.dp))
            PdfFormPaneHost(
                initialPane = PdfFormPane.PDF,
                pdf = { paneModifier ->
                    DocumentPreviewPane(
                        document = state.document,
                        loadEpoch = state.loadEpoch,
                        pdfActions = remember(documentId, viewModel) { DocumentPdfActions(documentId, viewModel) },
                        modifier = paneModifier,
                    )
                },
                form = { paneModifier ->
                    Column(
                        paneModifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        DocumentMetadataColumn(state, access.mayWrite, mayCapture, viewModel::updateForm, onCapture, onOpenInvoice)
                    }
                },
            )
        }
    }

    if (state.showCancelDialog) {
        CancelInvoiceDialog(
            filename = state.document?.filename.orEmpty(),
            reason = state.cancelReason,
            reasonTooLong = state.cancelReasonTooLong,
            canConfirm = state.cancelReasonValid,
            isCancelling = state.isCancelling,
            onReasonChange = viewModel::updateCancelReason,
            onConfirm = { viewModel.confirmCancelInvoice(documentId) },
            onDismiss = viewModel::dismissCancelInvoice,
        )
    }

    if (state.showDeleteDialog) {
        DeleteDocumentDialog(
            filename = state.document?.filename.orEmpty(),
            isDeleting = state.isDeleting,
            onConfirm = { viewModel.confirmDelete(documentId, onDeleted = onBack) },
            onDismiss = viewModel::dismissDelete,
        )
    }

    // The document and its metadata load in parallel; until both are in, the panes have nothing to show.
    if (state.isLoading) {
        PosLoadingOverlay(title = str(AdminStringKeys.DocumentDetailTitle))
    }
}

/**
 * The left pane: the document itself, rendered the way its media type allows.
 *
 * A PDF goes to the shared `PdfViewer` ([DocumentPdfPane]), streamed from the document's URL. What a
 * browser would make of a CAMT XML or an MT940 text blob is not a preview, so a parsed bank statement
 * shows its rows instead, and the raw file stays reachable through the header bar's download button.
 *
 * A null [document] keeps the PDF branch (see [previewKind]) but shows nothing yet: the viewer needs
 * the document's URL, and switching panes mid-load would flash the "no preview" text at every PDF.
 */
@Composable
private fun DocumentPreviewPane(
    document: DocumentDto.DocumentResponseDto?,
    loadEpoch: Int,
    pdfActions: DocumentPdfActions,
    modifier: Modifier = Modifier,
) {
    val transactions = document?.bankStatementMetadata?.transactions.orEmpty()
    when {
        previewKind(document?.mediaType) == DocumentPreviewKind.PDF ->
            if (document == null) Box(modifier) else DocumentPdfPane(document, loadEpoch, pdfActions, modifier)

        transactions.isNotEmpty() ->
            StatementTransactionTable(transactions, modifier = modifier)

        else ->
            DocumentNoPreviewPane(modifier = modifier)
    }
}

/**
 * The shared viewer on the document's own URL, with this panel's bearer ([AdminPdfTokenProvider]).
 * The source is remembered per document, URL and [loadEpoch]: `PdfViewer` compares sources by identity, so a
 * fresh one on every recomposition would reopen the file each time (a save hands over a new, equal DTO),
 * while a screen reload (the error dialog's Retry) must reopen it. A failed open offers its own Retry inside
 * the viewer; no `onClose` is passed, because the header's back arrow is always on screen.
 */
@Composable
private fun DocumentPdfPane(
    document: DocumentDto.DocumentResponseDto,
    loadEpoch: Int,
    pdfActions: DocumentPdfActions,
    modifier: Modifier,
) {
    val tokenProvider = koinInject<AdminPdfTokenProvider>()
    val baseUrl = koinInject<HttpCall>().getBaseUrl()
    val source =
        remember(document.id, document.downloadUrl, baseUrl, loadEpoch) {
            PdfSource.Url(resolveApiUrl(document.downloadUrl, baseUrl, window.location.origin), tokenProvider)
        }
    PdfViewer(
        source = source,
        fileName = document.filename,
        modifier = modifier,
        // Only for an open document: printing a file the viewer could not open, or still loading, helps nobody.
        extraActions = { ready ->
            PosIconButton(
                icon = Icons.Filled.Print,
                contentDescription = str(AdminStringKeys.DocumentPrint),
                onClick = pdfActions::print,
                enabled = ready,
            )
            PosIconButton(
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = str(AdminStringKeys.DocumentOpenInTab),
                onClick = { pdfActions.openInTab(document.mediaType) },
                enabled = ready,
            )
        },
    )
}

/**
 * Dispatches the type-specific view by [DocumentDetailState.document]'s `fileType`.
 *
 * Only [DocumentType.BANK_STATEMENT] still has an *editable* form here. An invoice gets the capture
 * entry point plus the read-only [DocumentInfoCard]: its data belongs to the normalized model, which
 * the capture screen owns, and the legacy `PUT /{id}/invoice-metadata` editor was removed in Stage 4
 * because it wrote a second, contradicting copy of the same eight fields — and could still overwrite
 * a hand-captured (`MANUAL`) invoice through `LegacyInvoiceSync`, which the Stage-1 provenance guard
 * only protects `EINVOICE_XML` rows from.
 *
 * Every other type falls back to [DocumentInfoCard] too.
 */
@Composable
private fun DocumentMetadataColumn(
    state: DocumentDetailState,
    canWrite: Boolean,
    mayCapture: Boolean,
    onFormChange: ((DocumentMetadataForm) -> DocumentMetadataForm) -> Unit,
    onCapture: (Long) -> Unit,
    onOpenInvoice: (Long) -> Unit,
) {
    val readOnly = !canWrite
    state.invoiceStorno?.let { storno -> InvoiceStornoNotice(storno, onOpenInvoice) }
    when (DocumentType.fromStringOrNull(state.document?.fileType)) {
        DocumentType.INVOICE -> {
            if (mayCapture) InvoiceEntryCard(state.document, onCapture, onOpenInvoice)
            state.document?.let { DocumentHistoryCard(it, onOpenInvoice) }
            DocumentInfoCard(state.document)
        }
        DocumentType.BANK_STATEMENT -> {
            val focus = rememberPosFormFocus<DocumentMetadataField>()
            LaunchedEffect(state.validationAttempt) {
                if (state.validationAttempt > 0) focus.revealFirst(DocumentMetadataField.entries, state.fieldErrors)
            }
            BankStatementMetadataForm(
                form = state.form,
                readOnly = readOnly,
                onChange = onFormChange,
                errors = state.fieldErrors,
                anchor = { field -> with(focus) { Modifier.formAnchor(field) } },
            )
        }
        else ->
            DocumentInfoCard(state.document)
    }
}
