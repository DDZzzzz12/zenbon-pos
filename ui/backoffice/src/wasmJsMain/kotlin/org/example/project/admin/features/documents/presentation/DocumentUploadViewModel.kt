package org.example.project.admin.features.documents.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.data.remote.DocumentManagementApiClient
import org.example.project.core.domain.enums.document.DocumentType
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError
import org.example.project.core.utils.PdfEncryptionProbe
import org.example.project.core.utils.file.FilePickResult
import org.example.project.core.utils.file.PickedFile

/**
 * Picks a file and uploads it, and reports nothing but the resulting document id.
 *
 * This deliberately knows **only** about uploading. `uploadDocument` takes some thirty parameters —
 * vendor name, invoice number, due date, IBAN, opening balance and the rest — and every one of them
 * is a statement about what the document *means*. None are accepted here: the moment this passed an
 * `invoiceNumber` through it would stop being an upload step and start being an invoice form, and
 * the two callers that need it (the Kassenbuch entry form, and the Belege/DMS screen after the
 * branches merge) disagree about what a document means. `fileType` is the one exception, and it is
 * not metadata but routing: the server stores the file under it.
 *
 * The picker arrives as [pickFile] rather than being called directly, which is what keeps this
 * class free of any one platform's file dialog: adminApp's DI supplies the browser
 * `<input type=file>` implementation, and a desktop or Android host would supply its own without
 * this file changing. It is the same hoisting `ShopLogoSection` uses for `onPickImage`, and the
 * reason moving this component into `shared-ui` later is a file move rather than a redesign.
 */

/** The status the upload answers when the archive already holds this exact content. */
private const val DUPLICATE_CONTENT_STATUS = 409

/**
 * The 409 the server answers when an identical file is already archived.
 *
 * Matched on the code rather than the message: the detail text is prose and not a contract.
 */
private fun UiError.isDuplicateContent(): Boolean =
    this is UiError.FromServer &&
        code == PosErrorCode.VALIDATION_FAILED &&
        httpStatus == DUPLICATE_CONTENT_STATUS

/** The 422s the server answers when an unlock was requested and the password did not do it. */
private fun UiError.isPasswordRefusal(): Boolean =
    this is UiError.FromServer &&
        (code == PosErrorCode.PDF_PASSWORD_REQUIRED || code == PosErrorCode.PDF_PASSWORD_INCORRECT)

private val SYSTEM_FILED_TYPES = setOf(DocumentType.ZBON_REPORT.name, DocumentType.OUTGOING_INVOICE.name)

private val DEFAULT_UPLOAD_TYPES =
    listOf(DocumentType.INVOICE, DocumentType.EIGENBELEG, DocumentType.CONTRACTS, DocumentType.BANK_STATEMENT).map { it.name }

/** A picked file waiting on the operator's unlock decision. Held privately: it carries the file's bytes. */
private class PendingUpload(
    val file: PickedFile,
    val fileType: String,
    val onUploaded: (Long) -> Unit,
)

class DocumentUploadViewModel(
    private val documentApiClient: DocumentManagementApiClient,
    private val pickFile: suspend (accept: String) -> FilePickResult,
) : ViewModel() {
    private val log = Logger.withTag("doc-upload")

    var state by mutableStateOf(DocumentUploadState())
        private set

    private var pending: PendingUpload? = null

    /**
     * The types the upload dialog offers: what the server lists, minus the two it files itself
     * (Z-Bon reports are generated, outgoing invoices are issued). Starts as the built-in manual
     * ones so the dialog is usable before, or without, the server's answer.
     */
    var uploadTypes by mutableStateOf(DEFAULT_UPLOAD_TYPES)
        private set

    fun loadUploadTypes() {
        viewModelScope.launch {
            runCatchingApi { documentApiClient.getDocumentTypes() }
                .onSuccess { types -> uploadTypes = types.filterNot { it in SYSTEM_FILED_TYPES }.ifEmpty { DEFAULT_UPLOAD_TYPES } }
                .onError { uiError -> log.w { "document types load failed, keeping the built-in list: $uiError" } }
        }
    }

    /** Uploads a file the caller already holds (a finished scan); same routing as a picked file. */
    fun uploadPicked(
        file: PickedFile,
        fileType: String,
        onUploaded: (Long) -> Unit,
    ) {
        if (state.isUploading) {
            log.d { "scan upload suppressed: an upload is already in flight" }
            return
        }
        viewModelScope.launch { routePicked(file, fileType, onUploaded) }
    }

    fun clearError() {
        state = state.copy(error = null)
    }

    /**
     * Drops everything, including a finished upload's id.
     *
     * adminApp shares **one `ViewModelStoreOwner` app-wide**, so this instance outlives the form
     * that hosts it: without an explicit reset, the next form to mount the section would open
     * already holding the previous booking's Beleg and could be submitted against it. The section
     * calls this on mount; see `DocumentUploadSection`'s `resetKey`.
     */
    fun reset() {
        pending = null
        state = DocumentUploadState()
    }

    /**
     * Opens the injected file dialog and uploads whatever comes back.
     *
     * A **cancel is not a failure** — [FilePickResult] keeps the three outcomes apart precisely so
     * that dismissing the dialog leaves the state untouched and silent. Only an unreadable file is
     * an error, and it is the section's own, not the server's.
     *
     * [onUploaded] is invoked once, on success, with the new document id. It is passed per call
     * rather than held on the ViewModel so no UI callback outlives the composition that supplied it.
     */
    fun pickAndUpload(
        fileType: String,
        accept: String,
        onUploaded: (Long) -> Unit,
    ) {
        if (state.isUploading) {
            log.d { "file dialog suppressed: an upload is already in flight" }
            return
        }
        viewModelScope.launch {
            val picked = pickFileOrNull(accept) ?: return@launch
            routePicked(picked, fileType, onUploaded)
        }
    }

    /** The three-way pick result narrowed to "a file to upload, or nothing to do". */
    private suspend fun pickFileOrNull(accept: String): PickedFile? =
        when (val result = pickFile(accept)) {
            is FilePickResult.Picked -> result.file
            FilePickResult.Cancelled -> {
                log.d { "file dialog dismissed; nothing uploaded" }
                null
            }
            FilePickResult.ReadFailed -> {
                log.w { "the chosen file could not be read by the browser" }
                state = state.copy(error = UiError.Unknown(messageKey = AdminStringKeys.DocumentUploadReadFailed))
                null
            }
        }

    /** A locked-looking PDF asks the operator first; everything else uploads as before. */
    private suspend fun routePicked(
        file: PickedFile,
        fileType: String,
        onUploaded: (Long) -> Unit,
    ) {
        if (PdfEncryptionProbe.looksEncrypted(file.bytes)) {
            pending = PendingUpload(file, fileType, onUploaded)
            state = DocumentUploadState(fileName = file.fileName, unlockPrompt = UnlockPrompt(file.fileName))
        } else {
            upload(file, fileType, onUploaded, unlockPassword = null)
        }
    }

    /** "Yes, unlock it." [password] may be empty (a PDF that only restricts editing). Not retained. */
    fun submitUnlockPassword(password: String) {
        val waiting = pending ?: return
        if (state.isUploading) return
        viewModelScope.launch { upload(waiting.file, waiting.fileType, waiting.onUploaded, unlockPassword = password) }
    }

    /** "No" — moves to the warning; nothing is sent yet. */
    fun declineUnlock() {
        state = state.copy(unlockPrompt = state.unlockPrompt?.copy(step = UnlockStep.ConfirmDecline))
    }

    fun backToUnlockPrompt() {
        state = state.copy(unlockPrompt = state.unlockPrompt?.copy(step = UnlockStep.Ask))
    }

    /** The warning was read and confirmed: archive the PDF exactly as it is, with no password field. */
    fun confirmStoreLocked() {
        val waiting = pending ?: return
        if (state.isUploading) return
        viewModelScope.launch { upload(waiting.file, waiting.fileType, waiting.onUploaded, unlockPassword = null) }
    }

    /** The dialog was dismissed: the upload is abandoned and the file forgotten. */
    fun cancelUnlock() {
        pending = null
        state = DocumentUploadState()
    }

    private suspend fun upload(
        file: PickedFile,
        fileType: String,
        onUploaded: (Long) -> Unit,
        unlockPassword: String?,
    ) {
        state = DocumentUploadState(fileName = file.fileName, isUploading = true, unlockPrompt = state.unlockPrompt)
        runCatchingApi {
            documentApiClient.uploadDocument(
                fileBytes = file.bytes,
                fileName = file.fileName,
                fileType = fileType,
                unlockPassword = unlockPassword,
            )
        }.onSuccess { document ->
            pending = null
            val outcome = UploadOutcome.of(document)
            log.d { "uploaded '${file.fileName}' as document ${document.id}: $outcome" }
            state =
                state.copy(
                    isUploading = false,
                    unlockPrompt = null,
                    documentId = document.id,
                    outcome = outcome,
                    uploaded = document,
                )
            onUploaded(document.id)
        }.onError { uiError ->
            log.e { "upload of '${file.fileName}' failed" }
            if (unlockPassword != null && uiError.isPasswordRefusal()) {
                // The dialog stays open on the password step with the server's sentence; the file is
                // still pending, so the operator retries without picking it again.
                state =
                    state.copy(
                        isUploading = false,
                        unlockPrompt = state.unlockPrompt?.copy(step = UnlockStep.Ask, rejection = uiError),
                    )
            } else {
                pending = null
                // No `retry`: retrying would need the file's bytes kept alive in state for the lifetime
                // of an app-wide ViewModel, and picking the file again is both cheap and unambiguous.
                // A duplicate is not a failure to report as one: the archive already holds this exact
                // file, so nothing is wrong and nothing needs retrying. Surfacing the server's raw
                // validation error would leave the operator unsure whether anything had been filed.
                state =
                    if (uiError.isDuplicateContent()) {
                        state.copy(isUploading = false, unlockPrompt = null, outcome = UploadOutcome.Duplicate)
                    } else {
                        state.copy(isUploading = false, unlockPrompt = null, error = uiError)
                    }
            }
        }
    }
}
