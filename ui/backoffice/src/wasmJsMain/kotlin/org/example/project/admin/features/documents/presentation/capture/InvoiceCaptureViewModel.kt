package org.example.project.admin.features.documents.presentation.capture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.supplier.presentation.SupplierForm
import org.example.project.core.data.remote.DocumentManagementApiClient
import org.example.project.core.data.remote.InvoiceListApiClient
import org.example.project.core.data.remote.SupplierApiClient
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.domain.dto.supplier.SupplierDto
import org.example.project.core.domain.enums.document.ProcessingStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError

/** The document states that mean "nobody has entered this yet" — see the spec's workflow table. */

/** Matches the Lieferanten screen's own debounce; see `SEARCH_DEBOUNCE_MS` there. */
private const val SUPPLIER_SEARCH_DEBOUNCE_MS = 300L

/** Enough to recognise the right creditor, few enough not to bury the form under a list. */
private const val SUPPLIER_SUGGESTION_LIMIT = 6

private val NEEDS_ENTRY =
    setOf(ProcessingStatus.NONE, ProcessingStatus.UNPROCESSABLE, ProcessingStatus.FAILED)

/**
 * Drives the invoice capture screen.
 *
 * The load is two phases on purpose. The document and its bytes are fetched in parallel and a failure
 * there is a real failure. The invoice is fetched **separately**, because `getDocumentInvoice` 404s
 * for an uncaptured document — the ordinary case — and a 404 inside the same `coroutineScope` would
 * cancel the siblings and turn a normal empty form into an error dialog over the PDF.
 */
internal class InvoiceCaptureViewModel(
    private val api: DocumentManagementApiClient,
    private val invoiceApi: InvoiceListApiClient,
    private val supplierApi: SupplierApiClient,
) : ViewModel() {
    private val log = Logger.withTag("invoiceCapture")

    /** Cancelled on every keystroke, so only the last pause in typing reaches the server. */
    private var searchJob: Job? = null

    var state by mutableStateOf(InvoiceCaptureState())
        private set

    /** Drops unsaved edits: the form goes back to what was last loaded or saved. Called on Discard. */
    fun discardDraft() {
        state = state.copy(form = state.pristine)
    }

    fun load(
        documentId: Long,
        canManage: Boolean,
    ) {
        state = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            // coroutineScope so a child failure is caught by runCatchingApi rather than escaping to
            // viewModelScope — the wasm gotcha for parallel async calls.
            runCatchingApi {
                coroutineScope {
                    val doc = async { api.getDocumentById(documentId) }
                    val bytes = async { api.getDocumentFile(documentId) }
                    doc.await() to bytes.await()
                }
            }.onSuccess { (doc, bytes) ->
                state = state.copy(document = doc, fileBytes = bytes, fileMediaType = doc.mediaType)
                loadInvoice(documentId)
                parkAsInProgress(documentId, doc, canManage)
            }.onError(retry = { load(documentId, canManage) }) { uiError ->
                log.e { "capture $documentId load failed: $uiError" }
                state = state.copy(isLoading = false, error = uiError)
            }
        }
    }

    /**
     * The invoice-keyed entry point, for an invoice that has **no document** — a bare XRechnung XML
     * or one entered by hand. For such an invoice it touches the document API nowhere: there is no
     * record to read, no bytes to preview and no `processing_status` to park, and each of those calls
     * would 404. The one exception is an invoice that does name a Beleg (a Storno correction), whose
     * file is fetched for the preview — see [loadBelegFile].
     *
     * [canManage] carries no work here for exactly that reason — the only thing it could gate,
     * [parkAsInProgress], has no document to stamp. It stays on the signature so the two entry
     * points read alike, and it is threaded through the retry.
     */
    fun loadByInvoice(
        invoiceId: Long,
        canManage: Boolean,
    ) {
        state = state.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatchingApi { invoiceApi.getInvoice(invoiceId) }
                .onSuccess { invoice ->
                    log.d { "capture invoice $invoiceId: source=${invoice.extractionSource}, beleg=${invoice.sourceDocumentId}" }
                    state = state.seededFrom(invoice)
                    invoice.sourceDocumentId?.let { documentId -> loadBelegFile(invoiceId, documentId) }
                }.onError(retry = { loadByInvoice(invoiceId, canManage) }) { uiError ->
                    log.e { "capture invoice $invoiceId load failed: $uiError" }
                    state = state.copy(isLoading = false, error = uiError)
                }
        }
    }

    /**
     * The preview for an invoice-keyed load whose invoice does sit on a Beleg — a Storno correction,
     * which is opened by its own id but shares the original's document (R6-2).
     *
     * Only the bytes: [InvoiceCaptureState.document] stays null on purpose, because it switches
     * [confirmReviewed] and [rereadInvoice] to the document-keyed endpoints, and a Beleg carrying
     * both halves of a Storno has no single "its invoice" for `getDocumentInvoice` to return. A
     * failure is not an error dialog either — the form is fully usable without the preview, whose
     * pane then reads "PDF unavailable".
     */
    private suspend fun loadBelegFile(
        invoiceId: Long,
        documentId: Long,
    ) {
        runCatchingApi { api.getDocumentFile(documentId) }
            .onSuccess { bytes -> state = state.copy(fileBytes = bytes) }
            .onError { uiError -> log.w { "capture invoice $invoiceId: Beleg $documentId file not loaded: $uiError" } }
    }

    /**
     * A 404 here is **not** an error — it is the create case, and the reason this function exists
     * apart from [load]. Any other failure still surfaces.
     */
    private suspend fun loadInvoice(documentId: Long) {
        runCatchingApi { api.getDocumentInvoice(documentId) }
            .onSuccess { invoice ->
                log.d { "capture $documentId: invoice ${invoice.id} source=${invoice.extractionSource}" }
                state = state.seededFrom(invoice)
            }.onError { uiError ->
                if (uiError.isNotFound()) {
                    log.d { "capture $documentId: no invoice yet, opening an empty create form" }
                    state =
                        state.copy(
                            isLoading = false,
                            invoice = null,
                            mode = InvoiceCaptureMode.Create,
                            form = InvoiceCaptureForm(),
                            pristine = InvoiceCaptureForm(),
                        )
                    return@onError
                }
                log.e { "capture $documentId invoice load failed: $uiError" }
                state = state.copy(isLoading = false, error = uiError)
            }
    }

    /**
     * Moves a document that needs entry to `IN_PROGRESS` so the work queue shows that somebody
     * started it. Fire-and-forget: this is bookkeeping, and a failure must never block capture — the
     * spec accepts a stale `IN_PROGRESS` as the price of not deriving the state.
     */
    private fun parkAsInProgress(
        documentId: Long,
        doc: DocumentDto.DocumentResponseDto,
        canManage: Boolean,
    ) {
        if (!canManage || doc.processingStatus !in NEEDS_ENTRY) return
        viewModelScope.launch {
            runCatchingApi {
                api.updateDocumentStatus(
                    documentId,
                    DocumentDto.UpdateDocumentStatusRequestDto(ProcessingStatus.IN_PROGRESS),
                )
            }.onSuccess { updated -> state = state.copy(document = updated) }
                .onError { uiError -> log.w { "capture $documentId: IN_PROGRESS not recorded: $uiError" } }
        }
    }

    /**
     * Validate, then route.
     *
     * Validation runs first and blocks the dispatch outright — the spec is explicit that the form is
     * the only thing between an operator and a rejected DATEV import, since the server enforces max
     * lengths but none of the numeric or pattern rules.
     *
     * Then: `Create` POSTs the content and books afterwards if anything was typed; `Edit` writes only
     * the scopes that are actually dirty, on the two invoice-keyed endpoints. The content write is
     * skipped entirely for an `EINVOICE_XML` invoice — the UI already disables those fields, and this
     * is the second half of that guard.
     */
    fun save() = saveThen(onSaved = null)

    /**
     * [save], then [onSaved] once it has succeeded — "Speichern und zuordnen", which carries on to the
     * Zuordnung queue. A refused validation or a failed request never calls it, so the operator stays
     * on the form with the error; and a form with nothing to write still counts as saved (the form
     * re-pins `pristine`, so the leave guard has nothing to ask about).
     */
    fun saveThen(onSaved: (() -> Unit)?) {
        // T9-19: a cancelled invoice and its correction are frozen; the form is disabled, and this is
        // the second half of that guard (the server refuses with INVOICE_CANCELLED_READ_ONLY).
        if (state.stornoReadOnly) return
        if (!showValidationVerdict()) return
        state = state.copy(isSaving = true, error = null)
        viewModelScope.launch {
            // Read BEFORE the save, which re-baselines `pristine` and would make every save look
            // like a no-op afterwards.
            val wrote = state.willIssueWrite
            performSave()
            if (state.error != null) return@launch
            if (wrote) state = state.copy(successMessage = AdminStringKeys.InvoiceCaptureSaved)
            onSaved?.invoke()
        }
    }

    /**
     * What this screen is working on, for the log only. There is no single id: the document-keyed
     * entry point has a document, the invoice-keyed one has only an invoice, and neither should be
     * faked into the other's shape — a `0L` sentinel at a real call site is exactly what this avoids.
     */
    private val subject: String
        get() = state.document?.id?.let { "document $it" } ?: "invoice ${state.invoice?.id}"

    /**
     * Renders the verdict of the pure validators into state and reports whether a save may proceed.
     * Split out so [confirmReviewed] can reuse it without duplicating the three assignments.
     */
    private fun showValidationVerdict(): Boolean {
        val errors = validateCapture(state.form)
        val rowIssues = vatRowErrors(state.form)
        state =
            state.copy(
                validationShown = true,
                fieldErrors = errors,
                rowErrors = rowIssues,
                warnings = captureWarnings(state.form),
            )
        if (errors.isEmpty() && rowIssues.isEmpty()) return true
        state = state.copy(validationAttempt = state.validationAttempt + 1)
        log.w { "capture $subject save blocked: ${errors.keys} rows=${rowIssues.keys}" }
        return false
    }

    /**
     * `Create` is only reachable from the document-keyed entry point — the invoice-keyed one always
     * loads an existing invoice, so it is always in `Edit`. A `Create` with no document is therefore
     * a state that cannot legitimately occur (a failed [loadByInvoice] is the nearest thing), and
     * there is no id to POST to; it is logged and dropped rather than invented.
     */
    private suspend fun performSave() {
        when (val mode = state.mode) {
            is InvoiceCaptureMode.Create -> {
                val documentId = state.document?.id
                if (documentId == null) {
                    log.e { "capture $subject: nothing to create against — no document to POST the invoice to" }
                    state = state.copy(isSaving = false)
                    return
                }
                createThenBook(documentId)
            }

            is InvoiceCaptureMode.Edit -> updateDirtyScopes(mode)
        }
    }

    private suspend fun createThenBook(documentId: Long) {
        val form = state.form
        runCatchingApi { api.createDocumentInvoice(documentId, null.toContentRequest(form)) }
            .onSuccess { created ->
                state =
                    state.copy(
                        invoice = created,
                        mode = InvoiceCaptureMode.Edit(created.id, created.extractionSource),
                    )
                if (!form.bookingEquals(InvoiceCaptureForm())) {
                    writeBooking(created.id, form)
                } else {
                    onSaveSuccess(form)
                }
            }.onError(retry = { save() }) { uiError -> onSaveError(uiError) }
    }

    private suspend fun updateDirtyScopes(mode: InvoiceCaptureMode.Edit) {
        val form = state.form
        val contentDirty = state.contentDirty
        val bookingDirty = state.bookingDirty

        if (!contentDirty) {
            finishEdit(mode, form, bookingDirty)
            return
        }
        runCatchingApi { api.updateInvoiceContent(mode.invoiceId, state.invoice.toContentRequest(form)) }
            .onSuccess { updated ->
                state = state.copy(invoice = updated)
                finishEdit(mode, form, bookingDirty)
            }.onError(retry = { save() }) { uiError -> onSaveError(uiError) }
    }

    private suspend fun finishEdit(
        mode: InvoiceCaptureMode.Edit,
        form: InvoiceCaptureForm,
        bookingDirty: Boolean,
    ) {
        if (bookingDirty) {
            writeBooking(mode.invoiceId, form)
        } else {
            onSaveSuccess(form)
        }
    }

    private suspend fun writeBooking(
        invoiceId: Long,
        form: InvoiceCaptureForm,
    ) {
        runCatchingApi { api.updateInvoiceBooking(invoiceId, form.toBookingRequest(state.pristine)) }
            .onSuccess { updated ->
                state = state.copy(invoice = updated)
                onSaveSuccess(form)
            }.onError(retry = { save() }) { uiError -> onSaveError(uiError) }
    }

    /**
     * Deliberately raises no confirmation. The two entry points do that, because only they know
     * *which* gesture the operator made — [save] confirms the save, [confirmReviewed] confirms the
     * review — and only [save] knows whether a request was actually issued.
     */
    private fun onSaveSuccess(savedForm: InvoiceCaptureForm) {
        log.d { "capture $subject saved" }
        state = state.copy(isSaving = false, pristine = savedForm)
    }

    /**
     * A 409 `INVOICE_SUPPLIER_CONTENT_READ_ONLY` is **a bug surfacing, not a workflow**: the screen
     * disables every supplier field for an `EINVOICE_XML` invoice, so the request should never have
     * been sent. The operator still deserves a sentence they can act on rather than the server's
     * detail string, and the state is re-read so the form stops disagreeing with the database.
     *
     * If this fires in the wild, the provenance flag was stale — a second client edited the invoice
     * between this screen's load and its save.
     */
    private fun onSaveError(uiError: UiError) {
        log.e { "capture $subject save failed: $uiError" }
        val readOnlyConflict =
            uiError is UiError.FromServer && uiError.code == PosErrorCode.INVOICE_SUPPLIER_CONTENT_READ_ONLY
        val shown =
            if (readOnlyConflict) {
                (uiError as UiError.FromServer).copy(messageKey = AdminStringKeys.InvoiceCaptureReadOnlyConflict)
            } else {
                uiError
            }
        state = state.copy(isSaving = false, error = shown)
        if (readOnlyConflict) viewModelScope.launch { rereadInvoice() }
    }

    /**
     * Re-reads the invoice from whichever key this screen actually has. The document-keyed path goes
     * back through [loadInvoice] (where a 404 still means "no invoice yet"); the invoice-keyed one
     * has no document to ask about and reads the invoice directly.
     */
    private suspend fun rereadInvoice() {
        val documentId = state.document?.id
        if (documentId != null) {
            loadInvoice(documentId)
            return
        }
        val invoiceId = state.invoice?.id ?: return
        runCatchingApi { invoiceApi.getInvoice(invoiceId) }
            .onSuccess { invoice -> state = state.seededFrom(invoice) }
            .onError { uiError -> log.w { "capture invoice $invoiceId re-read failed: $uiError" } }
    }

    /**
     * The *Als geprüft markieren* action: save, then move the document to `REVIEWED`.
     *
     * The order matters and is not negotiable — `REVIEWED` asserts a human confirmed **this data**,
     * so it must never be written for data the server has not accepted. A validation failure or a
     * save failure therefore leaves the status exactly where it was.
     *
     * It awaits [performSave] directly rather than firing [save] and spinning on `isSaving`: the
     * suspend function is the settled outcome, and a spin on a state flag is a smell under a
     * `StandardTestDispatcher`.
     *
     * The confirmation is the review, not the save. One gesture earns one toast, and it names the
     * outcome the operator asked for — which is also the only accurate thing to say, because the
     * save leg here is frequently a no-op: an operator who reads a form and presses *Als geprüft
     * markieren* without touching a field leaves no dirty scope, so nothing goes on the wire and
     * "Rechnungsdaten gespeichert" would be reporting a request that was never issued.
     */
    fun confirmReviewed() {
        // R6-1: the screen disables the action on either half of a Storno; this is the second half
        // of that guard. Returning before validation matters — the verdict would flag fields the
        // operator has no way to edit.
        if (state.stornoReadOnly) {
            log.d { "capture $subject: not confirming, the invoice is one half of a Storno" }
            return
        }
        if (!showValidationVerdict()) return
        state = state.copy(isSaving = true, error = null)
        viewModelScope.launch {
            performSave()
            if (state.error != null || state.isDirty) {
                log.w { "capture $subject: not marking reviewed, the save did not land cleanly" }
                return@launch
            }
            val document = state.document
            if (document == null) {
                markInvoiceReviewed()
                return@launch
            }
            runCatchingApi {
                api.updateDocumentStatus(
                    document.id,
                    DocumentDto.UpdateDocumentStatusRequestDto(ProcessingStatus.REVIEWED),
                )
            }.onSuccess { updated ->
                log.d { "capture document ${document.id} marked REVIEWED" }
                state = state.copy(document = updated, successMessage = AdminStringKeys.InvoiceCaptureReviewed)
            }.onError { uiError -> state = state.copy(error = uiError) }
        }
    }

    /**
     * Confirmation for a form opened by **invoice id** (no `state.document`). It stamps
     * `invoice.reviewed_at` through the invoice-keyed endpoint the Rechnungen list already uses; hiding
     * the action instead would leave such an invoice in *Zu prüfen* permanently.
     *
     * When the invoice was read from or captured against a document (`sourceDocumentId`), that document
     * is moved to `REVIEWED` too: the "Prüfen" card on the Beleg opens the form by invoice id, and
     * without this the Beleg-Verlauf, the Belege list and the Erledigt count would never see the review.
     * An invoice with no source document has no `processing_status` to move and keeps the stamp only.
     * (`state.document` stays null on purpose, see [loadBelegFile].)
     */
    private suspend fun markInvoiceReviewed() {
        val invoiceId = state.invoice?.id
        if (invoiceId == null) {
            log.e { "capture $subject: nothing to confirm — neither a document nor a stored invoice" }
            state = state.copy(isSaving = false)
            return
        }
        runCatchingApi { invoiceApi.markReviewed(invoiceId) }
            .onSuccess {
                log.d { "capture invoice $invoiceId marked reviewed" }
                markSourceDocumentReviewed(state.invoice?.sourceDocumentId)
            }.onError { uiError -> state = state.copy(error = uiError) }
    }

    /** The second half of [markInvoiceReviewed]: the success message only once the document moved too. */
    private suspend fun markSourceDocumentReviewed(sourceDocumentId: Long?) {
        if (sourceDocumentId == null) {
            state = state.copy(successMessage = AdminStringKeys.InvoiceCaptureReviewed)
            return
        }
        runCatchingApi {
            api.updateDocumentStatus(sourceDocumentId, DocumentDto.UpdateDocumentStatusRequestDto(ProcessingStatus.REVIEWED))
        }.onSuccess {
            log.d { "capture: source document $sourceDocumentId marked REVIEWED" }
            state = state.copy(successMessage = AdminStringKeys.InvoiceCaptureReviewed)
        }.onError { uiError ->
            log.e { "capture: invoice reviewed but document $sourceDocumentId not moved: $uiError" }
            state = state.copy(error = uiError)
        }
    }

    fun updateForm(mutate: (InvoiceCaptureForm) -> InvoiceCaptureForm) {
        val next = mutate(state.form)
        val sellerChanged = next.sellerName != state.form.sellerName
        state = state.copy(form = next).revalidatedIfShown()
        if (sellerChanged) searchSuppliers(next.sellerName)
    }

    /**
     * Attaches this capture to a creditor we already hold, by writing that creditor's name verbatim.
     *
     * There is no `supplierId` on the wire — the server resolves the link from the invoice's own
     * seller fields, matching VAT id, tax number, IBAN and then the normalized name. Writing the
     * catalogue's exact spelling is therefore what makes the link land on *this* creditor rather
     * than on a near-duplicate the operator's typing would otherwise create.
     *
     * The VAT id is deliberately **not** copied across. It is supplier-asserted content — what the
     * document says — and filling it from our own records would put an assertion on the invoice that
     * the paper may not make.
     */
    fun pickSupplier(supplier: SupplierDto.ResponseDto) {
        log.d { "capture attached to supplier ${supplier.id} (${supplier.name})" }
        state = state.copy(form = state.form.copy(sellerName = supplier.name)).revalidatedIfShown()
        // Kept, not cleared: the suggestion list is what `matchedSupplier` reads to confirm the name
        // now names a creditor we hold, which is the whole point of having picked one.
        searchJob?.cancel()
    }

    /**
     * Creates a creditor and attaches this capture to it in one move.
     *
     * The created supplier is pushed into the suggestions so [InvoiceCaptureState.matchedSupplier]
     * resolves immediately — otherwise the screen would still read "no creditor by that name" about
     * one that now exists, and the operator would have to retype the name to clear it.
     */
    fun createSupplier(form: SupplierForm) {
        if (state.isCreatingSupplier) return
        state = state.copy(isCreatingSupplier = true, supplierCreateError = null)
        viewModelScope.launch {
            runCatchingApi { supplierApi.create(form.toCreateRequest()) }
                .onSuccess { created ->
                    log.d { "creditor ${created.id} created from capture" }
                    searchJob?.cancel()
                    state =
                        state
                            .copy(
                                form = state.form.copy(sellerName = created.name),
                                supplierSuggestions = listOf(created),
                                supplierSearchAnswered = true,
                                isCreatingSupplier = false,
                                supplierCreateError = null,
                            ).revalidatedIfShown()
                }.onError { uiError ->
                    // Inline, not escalated: a taken VAT id is a correction to make in the dialog,
                    // and closing it to show a toast would discard everything already typed.
                    log.w { "creditor creation from capture failed: $uiError" }
                    state = state.copy(isCreatingSupplier = false, supplierCreateError = uiError)
                }
        }
    }

    fun clearSupplierCreateError() {
        state = state.copy(supplierCreateError = null)
    }

    /**
     * Looks the typed name up in the creditor catalogue, debounced.
     *
     * Debounced rather than per-keystroke because this fires while an operator types a supplier name
     * out of a scan, and the same reason [SEARCH_DEBOUNCE_MS] exists on the Lieferanten screen: the
     * answer to a prefix nobody has finished typing is not worth a round trip.
     */
    private fun searchSuppliers(term: String) {
        searchJob?.cancel()
        val query = term.trim()
        if (query.isBlank()) {
            state = state.copy(supplierSuggestions = emptyList(), isSearchingSuppliers = false, supplierSearchAnswered = false)
            return
        }
        state = state.copy(isSearchingSuppliers = true)
        searchJob =
            viewModelScope.launch {
                delay(SUPPLIER_SEARCH_DEBOUNCE_MS)
                runCatchingApi { supplierApi.list(search = query, limit = SUPPLIER_SUGGESTION_LIMIT) }
                    .onSuccess { response ->
                        // A stale answer must not overwrite a newer one: the operator has kept typing.
                        if (state.form.sellerName.trim() != query) return@onSuccess
                        state =
                            state.copy(
                                supplierSuggestions = response.items,
                                isSearchingSuppliers = false,
                                supplierSearchAnswered = true,
                            )
                    }.onError {
                        // Silent: a creditor lookup failing must not stop an operator capturing an
                        // invoice. The field stays free text, which is exactly what it was before.
                        log.w { "supplier lookup for '$query' failed; capture continues without suggestions" }
                        state = state.copy(isSearchingSuppliers = false, supplierSearchAnswered = false)
                    }
            }
    }

    fun addVatRow() = updateForm { it.copy(vatRows = it.vatRows + VatRowForm()) }

    /** The last row is never removable — an invoice with no VAT row cannot satisfy the required set. */
    fun removeVatRow(index: Int) =
        updateForm { form ->
            if (form.vatRows.size <= 1) form else form.copy(vatRows = form.vatRows.filterIndexed { i, _ -> i != index })
        }

    fun clearError() {
        state = state.copy(error = null)
    }

    fun clearSuccessMessage() {
        state = state.copy(successMessage = null)
    }

    private fun InvoiceCaptureState.revalidatedIfShown(): InvoiceCaptureState =
        if (!validationShown) {
            this
        } else {
            copy(
                fieldErrors = validateCapture(form),
                rowErrors = vatRowErrors(form),
                warnings = captureWarnings(form),
            )
        }
}

/**
 * Seeds the edit form from a loaded aggregate. Shared by both entry points so the invoice-keyed path
 * cannot drift from the document-keyed one — the form is identical, only the way in differs.
 */
private fun InvoiceCaptureState.seededFrom(invoice: DocumentDto.InvoiceDto): InvoiceCaptureState {
    val form = invoice.toForm()
    return copy(
        isLoading = false,
        invoice = invoice,
        mode = InvoiceCaptureMode.Edit(invoice.id, invoice.extractionSource),
        form = form,
        pristine = form,
    )
}

/** `RESOURCE_NOT_FOUND` means "this document has no invoice yet", never "something went wrong". */
private fun UiError.isNotFound(): Boolean = this is UiError.FromServer && code == PosErrorCode.RESOURCE_NOT_FOUND
