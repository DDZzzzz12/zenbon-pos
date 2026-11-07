package org.example.project.admin.features.documents.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.data.remote.DocumentManagementApiClient
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.enums.document.DocumentType
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.network.error.PosApiException
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.error.toUiError
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.utils.isBlankOrIsoDate
import org.example.project.core.utils.toCanonicalMoneyOrNull

/**
 * Drives the document detail screen: loads a document and its type-specific metadata in parallel (plus
 * the raw file bytes for a file with no in-page preview, see [fileBytesForPreview]), tracks an editable [DocumentMetadataForm] against its [DocumentDetailState.pristine]
 * snapshot for dirty-tracking, and saves/deletes.
 *
 * [save] validates every money field to a canonical decimal string before dispatching — money never
 * passes through a `Double` (see `platform/money/README.md`). It then routes to the type-specific update
 * endpoint. Since Stage 4 only [DocumentType.BANK_STATEMENT] is dispatched:
 * [DocumentManagementApiClient.updateBankStatementMetadata] is the sole metadata write adminApp
 * performs. [DocumentType.INVOICE] deliberately falls to the no-op branch — its legacy
 * `updateInvoiceMetadata` endpoint was deleted in Stage 5, because invoice
 * data belongs to the normalized model the capture screen owns. Every other type
 * (CONTRACTS, EIGENBELEG, ZBON_REPORT) has no metadata endpoint at all, so [save] is likewise a
 * no-op for them beyond re-pinning [DocumentDetailState.pristine] to clear the dirty flag.
 */
class DocumentDetailViewModel(
    private val api: DocumentManagementApiClient,
) : ViewModel() {
    private val log = Logger.withTag("documentDetail")

    var state by mutableStateOf(DocumentDetailState())
        private set

    fun load(documentId: Long) {
        state = state.copy(isLoading = true, error = null, loadEpoch = state.loadEpoch + 1)
        viewModelScope.launch {
            // coroutineScope so a child failure is caught by runCatchingApi rather than escaping
            // to viewModelScope, which is the wasm gotcha for parallel async calls.
            runCatchingApi {
                coroutineScope {
                    val doc = async { api.getDocumentById(documentId) }
                    val meta = async { api.getDocumentMetadata(documentId) }
                    val document = doc.await()
                    Triple(document, meta.await(), fileBytesForPreview(documentId, document))
                }
            }.onSuccess { (doc, meta, bytes) ->
                log.d { "document $documentId loaded: type=${doc.fileType} fileBytes=${bytes?.size}" }
                val form = meta.toForm()
                state =
                    state.copy(
                        isLoading = false,
                        document = doc,
                        metadata = meta,
                        fileBytes = bytes,
                        form = form,
                        pristine = form,
                    )
            }.onError(retry = { load(documentId) }) { uiError ->
                log.e { "document $documentId load failed: $uiError" }
                state = state.copy(isLoading = false, error = uiError)
            }
        }
    }

    /**
     * A PDF is not downloaded here: the shared `PdfViewer` streams it from the document's URL, and holding
     * a second copy of every opened file in the screen state is what this replaced. Every other file
     * type has no in-page preview, so its bytes are loaded up front for the header's download.
     */
    private suspend fun fileBytesForPreview(
        documentId: Long,
        document: DocumentDto.DocumentResponseDto,
    ): ByteArray? =
        if (previewKind(document.mediaType) == DocumentPreviewKind.PDF) {
            null
        } else {
            api.getDocumentFile(documentId)
        }

    /**
     * Hands the file's bytes to an action that needs them in hand (download, print, open in a tab): the
     * loaded copy when there is one, otherwise one fetch through the app's authenticated client. The
     * fetched bytes are not kept, so a PDF never sits in the screen state. A failure is raised like any
     * other load error and reported to [onFailed], so a caller that already opened a tab can close it.
     */
    fun withFileBytes(
        documentId: Long,
        onFailed: () -> Unit,
        onBytes: (ByteArray) -> Unit,
    ) {
        state.fileBytes?.let { bytes ->
            onBytes(bytes)
            return
        }
        viewModelScope.launch {
            runCatchingApi { api.getDocumentFile(documentId) }
                .onSuccess { bytes ->
                    log.d { "document $documentId file fetched for an action: ${bytes.size} bytes" }
                    onBytes(bytes)
                }.onError { uiError ->
                    log.e { "document $documentId file fetch failed: $uiError" }
                    state = state.copy(error = uiError)
                    onFailed()
                }
        }
    }

    fun updateForm(mutate: (DocumentMetadataForm) -> DocumentMetadataForm) {
        val next = mutate(state.form)
        // Only fields already flagged are re-checked: a fixed one loses its error as it is typed.
        val errors = if (state.fieldErrors.isEmpty()) emptyMap() else next.metadataFieldErrors().filterKeys { it in state.fieldErrors }
        state = state.copy(form = next, fieldErrors = errors)
    }

    fun save(documentId: Long) {
        val doc = state.document ?: return
        val form = state.form
        val isBankStatement = DocumentType.fromStringOrNull(doc.fileType) == DocumentType.BANK_STATEMENT
        val fieldErrors = if (isBankStatement) form.metadataFieldErrors() else emptyMap()
        if (fieldErrors.isNotEmpty()) {
            // Validate on press (UI-E2-06): the fields at fault are marked and the first focused.
            log.w { "document $documentId save blocked: ${fieldErrors.keys}" }
            state = state.copy(fieldErrors = fieldErrors, validationAttempt = state.validationAttempt + 1)
            return
        }
        if (!form.hasValidMoneyFields()) {
            log.w { "document $documentId save blocked: invalid money field" }
            state =
                state.copy(
                    error =
                        UiError.FromServer(
                            code = PosErrorCode.VALIDATION_FAILED,
                            traceId = null,
                            titleKey = CommonKeys.unknownErrorTitle,
                            messageKey = AdminStringKeys.DocumentInvalidAmount,
                            httpStatus = 0,
                        ),
                )
            return
        }
        state = state.copy(isSaving = true, error = null)
        viewModelScope.launch {
            when (DocumentType.fromStringOrNull(doc.fileType)) {
                DocumentType.BANK_STATEMENT -> saveBankStatementMetadata(documentId, form)
                else -> {
                    // INVOICE lands here deliberately: its legacy metadata endpoint still
                    // exists server-side but adminApp stopped calling it in Stage 4, because it
                    // wrote a second copy of data the capture screen owns. Every other type has
                    // no metadata endpoint at all.
                    log.i { "document $documentId save: nothing to persist for type=${doc.fileType}" }
                    state = state.copy(isSaving = false, pristine = form)
                }
            }
        }
    }

    private suspend fun saveBankStatementMetadata(
        documentId: Long,
        form: DocumentMetadataForm,
    ) {
        runCatchingApi {
            api.updateBankStatementMetadata(
                documentId = documentId,
                request =
                    DocumentDto.UpdateBankStatementMetadataRequestDto(
                        bankName = form.bankName.takeIf { it.isNotBlank() },
                        bic = form.bic.takeIf { it.isNotBlank() },
                        iban = form.iban.takeIf { it.isNotBlank() },
                        accountNumber = form.accountNumber.takeIf { it.isNotBlank() },
                        statementNumber = form.statementNumber.takeIf { it.isNotBlank() },
                        statementDate = form.statementDate.takeIf { it.isNotBlank() },
                        periodStart = form.periodStart.takeIf { it.isNotBlank() },
                        periodEnd = form.periodEnd.takeIf { it.isNotBlank() },
                        openingBalance = form.openingBalance.toCanonicalMoneyOrNull(),
                        closingBalance = form.closingBalance.toCanonicalMoneyOrNull(),
                        currency = form.currency.takeIf { it.isNotBlank() },
                    ),
            )
        }.onSuccess { updatedDoc -> onSaveSuccess(documentId, updatedDoc, form) }
            .onError(retry = { save(documentId) }) { uiError -> onSaveError(documentId, uiError) }
    }

    /**
     * Reached only from [saveBankStatementMetadata]'s success branch, i.e. only after a `PUT` was
     * actually issued and accepted — the no-op branch in [save] re-pins `pristine` itself and never
     * comes through here. So the confirmation cannot fire for a request that was never sent.
     */
    private fun onSaveSuccess(
        documentId: Long,
        updatedDoc: DocumentDto.DocumentResponseDto,
        savedForm: DocumentMetadataForm,
    ) {
        log.d { "document $documentId metadata saved" }
        state =
            state.copy(
                isSaving = false,
                document = updatedDoc,
                pristine = savedForm,
                successMessage = AdminStringKeys.DocumentSaved,
            )
    }

    private fun onSaveError(
        documentId: Long,
        uiError: UiError,
    ) {
        log.e { "document $documentId save failed: $uiError" }
        state = state.copy(isSaving = false, error = uiError)
    }

    fun requestDelete() {
        // The button is disabled in this case; the guard keeps a stray call from opening the dialog.
        if (state.deleteBlockedByInvoice) return
        state = state.copy(showDeleteDialog = true)
    }

    /** Names the reference the server refused on, as far as the loaded document tells (T9-19). */
    private fun blockedDeleteMessage(): StringKey =
        if (state.deleteBlockedByInvoice) {
            AdminStringKeys.DocumentDeleteBlockedInvoice
        } else {
            AdminStringKeys.DocumentDeleteBlockedLinked
        }

    fun requestCancelInvoice() {
        // The button is only offered while this holds; the guard keeps a stray call inert.
        if (!state.canCancelInvoice) return
        state = state.copy(showCancelDialog = true, cancelReason = "")
    }

    fun updateCancelReason(reason: String) {
        state = state.copy(cancelReason = reason)
    }

    fun dismissCancelInvoice() {
        if (state.isCancelling) return
        state = state.copy(showCancelDialog = false, cancelReason = "")
    }

    /**
     * Cancels the Beleg's invoice by a correction (T9-19), then re-reads the document so the Storno
     * notice replaces the action. Nothing is deleted — the document and its invoice stay archived.
     */
    fun confirmCancelInvoice(documentId: Long) {
        val invoiceId = state.document?.invoiceId ?: return
        if (!state.canCancelInvoice || !state.cancelReasonValid) return
        val reason = state.cancelReason.trim()
        state = state.copy(isCancelling = true, error = null)
        viewModelScope.launch {
            runCatchingApi {
                api.cancelInvoice(invoiceId, DocumentDto.CancelInvoiceRequestDto(reason))
                api.getDocumentById(documentId)
            }.onSuccess { updated ->
                log.d { "document $documentId: invoice $invoiceId cancelled" }
                state =
                    state.copy(
                        isCancelling = false,
                        showCancelDialog = false,
                        cancelReason = "",
                        document = updated,
                        successMessage = AdminStringKeys.DocumentCancelled,
                    )
            }.onError(retry = { confirmCancelInvoice(documentId) }) { uiError ->
                log.e { "document $documentId: cancelling invoice $invoiceId failed: $uiError" }
                state = state.copy(isCancelling = false, showCancelDialog = false, error = uiError)
            }
        }
    }

    fun dismissDelete() {
        state = state.copy(showDeleteDialog = false)
    }

    /** Drops unsaved edits: the form goes back to what was last loaded or saved. Called on Discard. */
    fun discardDraft() {
        state = state.copy(form = state.pristine)
    }

    /**
     * A `try/catch` is warranted here instead of [runCatchingApi] because [PosApiException] needs to
     * be inspected for [PosErrorCode.DELETE_HAS_REFERENCES] specifically (raised when the document is
     * still referenced by a `DocumentLink` or a `KassenbuchHeader`) and translated into a friendlier
     * message than the generic server envelope.
     *
     * [NetworkApiException] (thrown by `apiCall` on any `IOException`) and any other unexpected
     * [Throwable] are caught too — [DeleteDocumentDialog] disables both Cancel and Delete and blocks
     * click-outside-dismiss while [DocumentDetailState.isDeleting] is `true`, so letting either escape
     * unhandled leaves that flag stuck `true` forever with no way to close the dialog short of a page
     * reload.
     */
    fun confirmDelete(
        documentId: Long,
        onDeleted: () -> Unit,
    ) {
        state = state.copy(isDeleting = true, error = null)
        viewModelScope.launch {
            try {
                api.deleteDocument(documentId)
                log.d { "document $documentId deleted" }
                // Re-pin the form: the document is gone, so its edits are not "unsaved" and leaving must not ask.
                state = state.copy(isDeleting = false, showDeleteDialog = false, form = state.pristine)
                onDeleted()
            } catch (e: PosApiException) {
                if (e.code == PosErrorCode.DELETE_HAS_REFERENCES) {
                    log.w { "document $documentId delete blocked: still referenced by another record" }
                    state =
                        state.copy(
                            isDeleting = false,
                            showDeleteDialog = false,
                            error =
                                UiError.FromServer(
                                    code = e.code,
                                    traceId = e.traceId,
                                    titleKey = CommonKeys.unknownErrorTitle,
                                    messageKey = blockedDeleteMessage(),
                                    httpStatus = e.httpStatus,
                                    fieldErrors = e.fieldErrors,
                                    rawDetail = e.message,
                                ),
                        )
                } else {
                    log.e { "document $documentId delete failed: $e" }
                    failDelete(e.toUiError())
                }
            } catch (e: NetworkApiException) {
                log.e(e) { "document $documentId delete failed: transport error" }
                failDelete(e.toUiError())
            } catch (e: Throwable) {
                log.e(e) { "document $documentId delete failed: unexpected error" }
                failDelete(e.toUiError())
            }
        }
    }

    private fun failDelete(uiError: UiError) {
        state = state.copy(isDeleting = false, showDeleteDialog = false, error = uiError)
    }

    fun clearError() {
        state = state.copy(error = null)
    }

    fun clearSuccessMessage() {
        state = state.copy(successMessage = null)
    }
}

/**
 * The bank-statement fields' verdicts: the three dates must be dates (the date field hands over the
 * typed text when it is none), the two balances readable amounts. All optional — blank passes.
 */
private fun DocumentMetadataForm.metadataFieldErrors(): Map<DocumentMetadataField, StringKey> =
    buildMap {
        if (!isBlankOrIsoDate(statementDate)) put(DocumentMetadataField.STATEMENT_DATE, CommonKeys.dateInvalid)
        if (!isBlankOrIsoDate(periodStart)) put(DocumentMetadataField.PERIOD_START, CommonKeys.dateInvalid)
        if (!isBlankOrIsoDate(periodEnd)) put(DocumentMetadataField.PERIOD_END, CommonKeys.dateInvalid)
        if (openingBalance.isNotBlank() && openingBalance.toCanonicalMoneyOrNull() == null) {
            put(DocumentMetadataField.OPENING_BALANCE, AdminStringKeys.DocumentInvalidAmount)
        }
        if (closingBalance.isNotBlank() && closingBalance.toCanonicalMoneyOrNull() == null) {
            put(DocumentMetadataField.CLOSING_BALANCE, AdminStringKeys.DocumentInvalidAmount)
        }
    }

/** All five money fields on the form are optional — blank is valid — but a non-blank value must parse. */
private fun DocumentMetadataForm.hasValidMoneyFields(): Boolean =
    listOf(netAmount, grossAmount, taxAmount, openingBalance, closingBalance)
        .all { it.isBlank() || it.toCanonicalMoneyOrNull() != null }
