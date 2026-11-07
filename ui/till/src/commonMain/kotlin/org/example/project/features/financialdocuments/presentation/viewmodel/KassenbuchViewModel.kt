/**
 * ViewModel for Kassenbuch (Cash Book) management.
 *
 * Manages kassenbuch entry creation, history viewing, and detail dialogs
 * with proper state management and error handling.
 *
 * @property kassenbuchApiClient Client for kassenbuch API operations
 * @property zBonApiClient Client for fetching date range (last Z-Bon time)
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.financialdocuments.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.data.remote.CashBookApiClient
import org.example.project.core.data.remote.DocumentManagementApiClient
import org.example.project.core.data.remote.KassenbuchApiClient
import org.example.project.core.data.remote.KassenbuchCreateOutcome
import org.example.project.core.data.remote.ZBonApiClient
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.state.DateRange
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.utils.toCanonicalMoneyOrNull
import org.example.project.features.financialdocuments.presentation.state.FinancialDocumentsEvent
import org.example.project.features.financialdocuments.presentation.state.FinancialDocumentsEventBus
import org.example.project.features.financialdocuments.presentation.state.InvoiceMetadataInput
import org.example.project.features.financialdocuments.presentation.state.KassenbuchState
import org.example.project.money.Decimal
import org.example.project.money.sum
import org.example.project.ui.components.formatMoneyForDisplay
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * ViewModel for managing Kassenbuch functionality.
 *
 * Handles kassenbuch entry creation, history retrieval, filtering,
 * and dialog state management independently from main reports screen.
 */
@OptIn(ExperimentalTime::class)
class KassenbuchViewModel(
    private val kassenbuchApiClient: KassenbuchApiClient,
    private val cashBookApiClient: CashBookApiClient,
    private val zBonApiClient: ZBonApiClient,
    private val documentManagementApiClient: DocumentManagementApiClient,
    private val eventBus: FinancialDocumentsEventBus,
) : ViewModel() {
    private val logger = Logger.withTag("KassenbuchViewModel")

    private var loadJob: Job? = null

    private val _kassenbuchState = MutableStateFlow(KassenbuchState())
    val kassenbuchState: StateFlow<KassenbuchState> = _kassenbuchState.asStateFlow()

    init {
        viewModelScope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is FinancialDocumentsEvent.LinkingCompleted -> loadKassenbuchData()
                    is FinancialDocumentsEvent.KassenbuchEntryCreated -> loadKassenbuchData()
                    is FinancialDocumentsEvent.UploadCompleted -> {
                        val currentState = _kassenbuchState.value
                        val replaceHeaderId = currentState.pendingReplaceAfterUpload
                        val linkHeaderId = currentState.pendingLinkAfterUpload
                        when {
                            replaceHeaderId != null ->
                                replaceUploadedDocument(replaceHeaderId, event.documentId)
                            linkHeaderId != null ->
                                linkUploadedDocument(linkHeaderId, event.documentId)
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * Get start time from last Z-Bon for date range calculations.
     *
     * Used to determine the reporting period (from last Z-Bon end time to current time).
     *
     * @return Pair of start and end time in ISO format, or null if no Z-Bon exists
     */
    private suspend fun getStartTime(): Pair<String, String>? {
        val time =
            runCatchingApi { zBonApiClient.getLatestZBonEndTime() }
                .getOrElse { throw Exception("Error getting start time", it) }
        return if (time != null) {
            val startTime = LocalDateTime.parse(time)
            val endTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            Pair(startTime.toString(), endTime.toString())
        } else {
            null
        }
    }

    /**
     * Select a new date range for kassenbuch history and reload data.
     *
     * @param dateRange The new date range to apply
     */
    fun selectDateRange(dateRange: DateRange) {
        _kassenbuchState.update { currentState ->
            currentState.copy(selectedDateRange = dateRange)
        }
        loadKassenbuchData()
    }

    /**
     * Set custom date range for kassenbuch history.
     *
     * Updates the state with a custom date range and fetches
     * kassenbuch history for the specified period.
     *
     * @param startDate Start date in ISO format (YYYY-MM-DD)
     * @param endDate End date in ISO format (YYYY-MM-DD)
     */
    fun setCustomDateRange(
        startDate: String,
        endDate: String,
    ) {
        _kassenbuchState.update { currentState ->
            currentState.copy(
                selectedDateRange = DateRange.CUSTOM,
                customDateRange = Pair(startDate, endDate),
            )
        }
        loadKassenbuchData()
    }

    /**
     * Load kassenbuch data for the selected cash book: the book list, that book's balance and that
     * book's entry history for the selected date range.
     *
     * Every figure on the screen is scoped to the same single book. The server's `/balance` never
     * sums across books, so pairing it with an unfiltered history (as this screen once did) made
     * Einnahmen/Ausgaben count the Tresor while Kassenbestand counted only the Ladenkasse.
     *
     * A newer load cancels an older one still in flight, so switching books quickly cannot let a
     * slow response for the previous book overwrite the current one.
     */
    fun loadKassenbuchData() {
        logger.d { "KassenbuchViewModel.loadKassenbuchData: loading kassenbuch data" }
        loadJob?.cancel()
        loadJob =
            viewModelScope.launch {
                _kassenbuchState.update { it.copy(isLoading = true, error = null) }

                val booksResult = runCatchingApi { cashBookApiClient.listBooks() }
                val books =
                    booksResult.getOrElse {
                        booksResult.onError { uiError ->
                            _kassenbuchState.update { it.copy(isLoading = false, error = uiError) }
                        }
                        return@launch
                    }
                _kassenbuchState.update { state ->
                    state.copy(
                        cashBooks = books,
                        selectedCashBookId = resolveSelectedBook(books, state.selectedCashBookId),
                    )
                }

                val currentState = _kassenbuchState.value
                val bookId = currentState.selectedCashBookId
                val (startDate, endDate) = currentState.effectiveDatePair()

                val balanceResult = runCatchingApi { kassenbuchApiClient.getCurrentBalance(bookId) }
                val balance =
                    balanceResult
                        .getOrElse {
                            balanceResult.onError { uiError ->
                                _kassenbuchState.update { it.copy(isLoading = false, error = uiError) }
                            }
                            return@launch
                        }.let(::balanceForDisplay)

                runCatchingApi { kassenbuchApiClient.getEntryHistory(startDate, endDate, cashBookId = bookId) }
                    .onSuccess { entries ->
                        logger.d { "KassenbuchViewModel.loadKassenbuchData: loaded ${entries.size} entries for book $bookId" }
                        val (income, expenses) = computeKpiSums(entries)
                        _kassenbuchState.update {
                            it.copy(
                                entries = entries,
                                currentBalance = balance,
                                totalIncome = income,
                                totalExpenses = expenses,
                                isLoading = false,
                                error = null,
                            )
                        }
                    }.onError { uiError ->
                        _kassenbuchState.update {
                            it.copy(
                                entries = emptyList(),
                                currentBalance = balance,
                                isLoading = false,
                                error = uiError,
                            )
                        }
                    }
            }
    }

    /**
     * Switch the screen to another cash book and reload the list and all three summary cards for it.
     *
     * @param cashBookId An active book from [KassenbuchState.selectableCashBooks]
     */
    fun selectCashBook(cashBookId: Long) {
        if (cashBookId == _kassenbuchState.value.selectedCashBookId) return
        _kassenbuchState.update { it.copy(selectedCashBookId = cashBookId) }
        loadKassenbuchData()
    }

    /**
     * Keeps the operator's pick while it is still an active book; otherwise falls back to the
     * fiscal Ladenkasse (the till's own book), then to the first active book. `null` only when the
     * shop has no active book at all - the server then answers for its fiscal default.
     */
    private fun resolveSelectedBook(
        books: List<CashBookDto.ResponseDto>,
        current: Long?,
    ): Long? {
        val active = books.filter { it.isActive }
        if (current != null && active.any { it.id == current }) return current
        val fallback = active.firstOrNull { it.isFiscal } ?: active.minWithOrNull(compareBy({ it.sortOrder }, { it.name }))
        return fallback?.id
    }

    /** The book a created entry was booked into; `null` in the request means the fiscal Ladenkasse. */
    private fun bookedCashBookId(
        request: KassenbuchDto.CreateKassenbuchEntryRequestDto,
        state: KassenbuchState,
    ): Long? = request.cashBookId ?: state.cashBooks.firstOrNull { it.isFiscal }?.id ?: state.selectedCashBookId

    private fun KassenbuchState.effectiveDatePair(): Pair<String, String> =
        if (selectedDateRange == DateRange.CUSTOM && customDateRange != null) {
            customDateRange
        } else {
            selectedDateRange.toDatePair()
        }

    /**
     * Show kassenbuch panel and load initial data.
     */
    fun showPanel() {
        _kassenbuchState.update { currentState ->
            currentState.copy(isVisible = true)
        }
        loadKassenbuchData()
    }

    /**
     * Opens the manual transfer form, loading the shop's cash books so the pickers and the
     * available-balance hint can render.
     */
    fun showTransferDialog() {
        _kassenbuchState.update { it.copy(showTransferDialog = true) }
        viewModelScope.launch {
            runCatchingApi { cashBookApiClient.listBooks() }
                .onSuccess { books -> _kassenbuchState.update { it.copy(cashBooks = books) } }
                .onError { uiError -> _kassenbuchState.update { it.copy(error = uiError) } }
        }
    }

    fun hideTransferDialog() {
        _kassenbuchState.update { it.copy(showTransferDialog = false) }
    }

    /**
     * Books a manual Geldtransit between two cash books.
     *
     * On failure the dialog stays open with the operator's input intact — making someone re-key an
     * amount they already typed is how the wrong amount gets booked.
     *
     * @param request The validated transfer
     */
    fun createTransfer(request: CashBookDto.CreateTransferRequestDto) {
        _kassenbuchState.update { it.copy(isTransferring = true) }
        viewModelScope.launch {
            runCatchingApi { cashBookApiClient.createTransfer(request) }
                .onSuccess {
                    _kassenbuchState.update { it.copy(showTransferDialog = false, isTransferring = false) }
                    loadKassenbuchData()
                }.onError { uiError ->
                    _kassenbuchState.update { it.copy(isTransferring = false, error = uiError) }
                }
        }
    }

    /**
     * Hide kassenbuch panel and clear dialog states.
     */
    fun hidePanel() {
        _kassenbuchState.update { currentState ->
            currentState.copy(
                isVisible = false,
                showCreateDialog = false,
            )
        }
    }

    /**
     * Fetch full entry detail from server.
     *
     * @param headerId Header ID to fetch
     */
    fun fetchEntryDetail(headerId: Long) {
        logger.d { "KassenbuchViewModel.fetchEntryDetail: headerId=$headerId" }
        viewModelScope.launch {
            _kassenbuchState.update { it.copy(isLoadingDetails = true) }
            runCatchingApi { kassenbuchApiClient.getGroupedEntryDetail(headerId) }
                .onSuccess { detail ->
                    _kassenbuchState.update {
                        it.copy(selectedEntryDetail = detail, isLoadingDetails = false)
                    }
                }.onError { uiError ->
                    _kassenbuchState.update {
                        it.copy(isLoadingDetails = false, error = uiError)
                    }
                }
        }
    }

    /**
     * Clear selected entry and close detail dialog.
     */
    fun clearSelectedEntry() {
        _kassenbuchState.update { currentState ->
            currentState.copy(
                selectedEntryDetail = null,
                showLinkedPdfViewer = false,
                selectedLinkedDocumentId = null,
            )
        }
    }

    /**
     * Show linked PDF viewer for a specific document.
     *
     * @param documentId The ID of the linked document to preview
     */
    fun showLinkedPdf(documentId: Long) {
        _kassenbuchState.update {
            it.copy(
                showLinkedPdfViewer = true,
                selectedLinkedDocumentId = documentId,
                selectedEntryDetail = null,
            )
        }
    }

    /**
     * Unlink document from a Kassenbuch entry.
     *
     * @param headerId The Kassenbuch header ID to unlink the document from
     */
    fun unlinkDocument(headerId: Long) {
        logger.i { "KassenbuchViewModel.unlinkDocument: headerId=$headerId" }
        viewModelScope.launch {
            runCatchingApi { kassenbuchApiClient.unlinkDocument(headerId) }
                .onSuccess { detail ->
                    _kassenbuchState.update { it.copy(selectedEntryDetail = detail) }
                    loadKassenbuchData()
                }.onError { uiError ->
                    _kassenbuchState.update { it.copy(error = uiError) }
                }
        }
    }

    /**
     * Trigger upload dialog with auto-link to kassenbuch entry after upload completes.
     *
     * Sets pending link state and emits ShowUploadDialog event. When the upload
     * completes (UploadCompleted event), the uploaded document is automatically
     * linked to the specified kassenbuch header.
     *
     * @param headerId The kassenbuch header ID to link the uploaded document to
     */
    fun uploadAndLinkDocument(headerId: Long) {
        _kassenbuchState.update {
            it.copy(
                pendingLinkAfterUpload = headerId,
            )
        }
        viewModelScope.launch {
            eventBus.emit(FinancialDocumentsEvent.ShowUploadDialog)
        }
    }

    /**
     * Trigger upload dialog with auto-replace for the given kassenbuch header.
     *
     * Mirrors [uploadAndLinkDocument] but routes completion through
     * [replaceUploadedDocument] so the existing linked document is soft-replaced
     * rather than rejected by the 1:1 guard.
     *
     * @param headerId The kassenbuch header ID whose document will be replaced
     */
    fun replaceAndUploadDocument(headerId: Long) {
        _kassenbuchState.update {
            it.copy(pendingReplaceAfterUpload = headerId)
        }
        viewModelScope.launch {
            eventBus.emit(FinancialDocumentsEvent.ShowUploadDialog)
        }
    }

    /**
     * Replace the linked document on the pending kassenbuch header with the
     * freshly uploaded document. Called automatically after upload completes
     * when pendingReplaceAfterUpload is set.
     */
    private fun replaceUploadedDocument(
        headerId: Long,
        documentId: Long,
    ) {
        viewModelScope.launch {
            _kassenbuchState.update { it.copy(pendingReplaceAfterUpload = null) }
            runCatchingApi { kassenbuchApiClient.replaceDocument(headerId, documentId) }
                .onSuccess { detail ->
                    _kassenbuchState.update { it.copy(selectedEntryDetail = detail) }
                    loadKassenbuchData()
                    eventBus.emit(FinancialDocumentsEvent.LinkingCompleted)
                }.onError { uiError ->
                    _kassenbuchState.update { it.copy(error = uiError) }
                }
        }
    }

    /**
     * Open the correction (Storno) dialog for the given entry.
     */
    fun openCorrectionDialog(detail: KassenbuchDto.KassenbuchGroupedDetailDto) {
        _kassenbuchState.update { it.copy(showCorrectionDialog = true, correctionTarget = detail) }
    }

    /**
     * Dismiss the correction (Storno) dialog.
     */
    fun dismissCorrectionDialog() {
        _kassenbuchState.update { it.copy(showCorrectionDialog = false, correctionTarget = null) }
    }

    /**
     * Submit a correction (Storno + corrected re-entry) for the given header.
     *
     * When [fileBytes] is provided the file is uploaded first (mirroring
     * [createEntry]'s upload flow) and the resulting documentId is set as the
     * corrected re-entry's `replacementDocumentId`. When no file is provided the
     * request is sent as-is — `replacementDocumentId` stays null and the server
     * copies the original entry's document.
     *
     * On success closes the dialog, refreshes the kassenbuch list and emits a
     * [FinancialDocumentsEvent.LinkingCompleted] so dependent views reload.
     */
    fun submitCorrection(
        headerId: Long,
        request: KassenbuchDto.CorrectEntryRequestDto,
        fileBytes: ByteArray? = null,
        fileName: String? = null,
        fileType: String? = null,
    ) {
        viewModelScope.launch {
            _kassenbuchState.update { it.copy(isCorrecting = true) }

            val effectiveRequest =
                if (fileBytes != null && fileName != null) {
                    val uploadResult =
                        runCatchingApi {
                            documentManagementApiClient.uploadDocument(
                                fileBytes = fileBytes,
                                fileName = fileName,
                                fileType = fileType ?: "EIGENBELEG",
                            )
                        }
                    val uploadedDocumentId =
                        uploadResult
                            .getOrElse {
                                uploadResult.onError { uiError ->
                                    _kassenbuchState.update { it.copy(isCorrecting = false, error = uiError) }
                                }
                                return@launch
                            }.id
                    request.copy(replacementDocumentId = uploadedDocumentId)
                } else {
                    request
                }

            runCatchingApi { kassenbuchApiClient.correctEntry(headerId, effectiveRequest) }
                .onSuccess { detail ->
                    _kassenbuchState.update {
                        it.copy(
                            isCorrecting = false,
                            showCorrectionDialog = false,
                            correctionTarget = null,
                            selectedEntryDetail = detail,
                        )
                    }
                    loadKassenbuchData()
                    eventBus.emit(FinancialDocumentsEvent.LinkingCompleted)
                }.onError { uiError ->
                    _kassenbuchState.update { it.copy(isCorrecting = false, error = uiError) }
                }
        }
    }

    /**
     * Link an uploaded document to the pending kassenbuch header.
     *
     * Called automatically after upload completes when pendingLinkAfterUpload is set.
     */
    private fun linkUploadedDocument(
        headerId: Long,
        documentId: Long,
    ) {
        viewModelScope.launch {
            _kassenbuchState.update { it.copy(pendingLinkAfterUpload = null) }
            runCatchingApi { kassenbuchApiClient.linkDocument(headerId, documentId) }
                .onSuccess {
                    loadKassenbuchData()
                    eventBus.emit(FinancialDocumentsEvent.LinkingCompleted)
                }.onError { uiError ->
                    _kassenbuchState.update { it.copy(error = uiError) }
                }
        }
    }

    /**
     * Show create entry dialog.
     */
    fun showCreateDialog() {
        _kassenbuchState.update { currentState ->
            currentState.copy(showCreateDialog = true, availableCash = null)
        }
        loadAvailableCash()
    }

    /**
     * F59: what the fiscal Ladenkasse — where the till's form books — can pay out, for the form's
     * Kassenminus check. Fails soft: without it the form does not pre-check, and the server still
     * refuses an overdraft with KASSENBUCH_INSUFFICIENT_CASH.
     */
    private fun loadAvailableCash() {
        viewModelScope.launch {
            runCatchingApi { kassenbuchApiClient.getAvailableCash(null) }
                .onSuccess { available -> _kassenbuchState.update { it.copy(availableCash = available.availableCash) } }
                .onError { logger.w { "KassenbuchViewModel.loadAvailableCash: unavailable, the server still guards" } }
        }
    }

    /**
     * Hide create entry dialog.
     */
    fun hideCreateDialog() {
        _kassenbuchState.update { currentState ->
            currentState.copy(showCreateDialog = false)
        }
    }

    /**
     * Create a new kassenbuch entry with optional PDF attachment.
     *
     * Orchestrates a multi-step flow:
     * 1. Create the kassenbuch entry via API
     * 2. If fileBytes provided, upload the PDF document
     * 3. If upload succeeds, link the PDF to the kassenbuch entry
     *
     * Entry creation is the primary operation — if PDF upload or linking
     * fails, the entry is still kept and a warning is shown to the user.
     *
     * @param request Entry creation request with all DATEV fields
     * @param fileBytes Optional PDF file content to attach
     * @param fileName Optional original filename of the PDF
     */
    fun createEntry(
        request: KassenbuchDto.CreateKassenbuchEntryRequestDto,
        fileBytes: ByteArray? = null,
        fileName: String? = null,
        fileType: String? = null,
        invoiceMetadata: InvoiceMetadataInput? = null,
    ) {
        logger.i { "KassenbuchViewModel.createEntry: type=${request.entryType}, hasFile=${fileBytes != null}, fileType=$fileType" }
        viewModelScope.launch {
            // Validate request — skip the documentId check, since we fill it in
            // after uploading the attached file just below.
            val validationErrors =
                request
                    .validate()
                    .filterNot { it.contains("documentId", ignoreCase = true) }
            if (validationErrors.isNotEmpty()) {
                _kassenbuchState.update {
                    it.copy(
                        error =
                            UiError.Unknown(
                                titleKey = CommonKeys.unknownErrorTitle,
                                messageKey = CommonKeys.unknownErrorMessage,
                            ),
                    )
                }
                return@launch
            }

            _kassenbuchState.update { it.copy(isCreating = true) }

            if (fileBytes == null || fileName == null) {
                _kassenbuchState.update {
                    it.copy(
                        isCreating = false,
                        error =
                            UiError.Unknown(
                                titleKey = CommonKeys.unknownErrorTitle,
                                messageKey = CommonKeys.unknownErrorMessage,
                            ),
                    )
                }
                return@launch
            }

            val fallbackGrossAmount =
                request.lines
                    .mapNotNull { Decimal.parseOrNull(it.amount) }
                    .sum()
            val fallbackTaxRate =
                request.lines
                    .firstOrNull()
                    ?.vatRate
                    ?.takeIf { it.isNotBlank() }

            val uploadResult =
                runCatchingApi {
                    documentManagementApiClient.uploadDocument(
                        fileBytes = fileBytes,
                        fileName = fileName,
                        fileType = fileType ?: "INVOICE",
                        vendorName = if (fileType == "INVOICE") invoiceMetadata?.vendorName?.takeIf { it.isNotBlank() } else null,
                        invoiceNumber = if (fileType == "INVOICE") invoiceMetadata?.invoiceNumber?.takeIf { it.isNotBlank() } else null,
                        invoiceDate = if (fileType == "INVOICE") invoiceMetadata?.invoiceDate?.takeIf { it.isNotBlank() } else null,
                        dueDate = if (fileType == "INVOICE") invoiceMetadata?.dueDate?.takeIf { it.isNotBlank() } else null,
                        netAmount = if (fileType == "INVOICE") invoiceMetadata?.netAmount?.toCanonicalMoneyOrNull() else null,
                        grossAmount =
                            if (fileType == "INVOICE") {
                                (
                                    invoiceMetadata?.grossAmount?.toCanonicalMoneyOrNull()
                                        ?: fallbackGrossAmount.takeIf { it > Decimal.ZERO }?.toPlainString()
                                )
                            } else {
                                null
                            },
                        taxAmount = if (fileType == "INVOICE") invoiceMetadata?.taxAmount?.toCanonicalMoneyOrNull() else null,
                        taxRate =
                            if (fileType == "INVOICE") {
                                (
                                    invoiceMetadata?.taxRate?.toCanonicalMoneyOrNull()
                                        ?: fallbackTaxRate
                                )
                            } else {
                                null
                            },
                        currency =
                            if (fileType == "INVOICE") {
                                invoiceMetadata?.currency?.takeIf { it.isNotBlank() }
                                    ?: request.waehrung
                            } else {
                                null
                            },
                        iban = if (fileType == "INVOICE") invoiceMetadata?.iban?.takeIf { it.isNotBlank() } else null,
                        paymentReference = if (fileType == "INVOICE") invoiceMetadata?.paymentReference?.takeIf { it.isNotBlank() } else null,
                        supplierCity = if (fileType == "INVOICE") invoiceMetadata?.supplierCity?.takeIf { it.isNotBlank() } else null,
                        vatId = if (fileType == "INVOICE") invoiceMetadata?.vatId?.takeIf { it.isNotBlank() } else null,
                        accountNo =
                            if (fileType == "INVOICE") {
                                invoiceMetadata?.accountNo?.takeIf { it.isNotBlank() }
                                    ?: request.gegenkonto?.takeIf { it.isNotBlank() }
                            } else {
                                null
                            },
                        bpAccountNo = if (fileType == "INVOICE") invoiceMetadata?.bpAccountNo?.takeIf { it.isNotBlank() } else null,
                        deliveryDate = if (fileType == "INVOICE") invoiceMetadata?.deliveryDate?.takeIf { it.isNotBlank() } else null,
                        bookingText =
                            if (fileType == "INVOICE") {
                                invoiceMetadata?.bookingText?.takeIf { it.isNotBlank() }
                                    ?: request.belegtext.takeIf { it.isNotBlank() }
                            } else {
                                null
                            },
                    )
                }

            val uploadedDocumentId =
                uploadResult
                    .getOrElse {
                        uploadResult.onError { uiError ->
                            _kassenbuchState.update { it.copy(isCreating = false, error = uiError) }
                        }
                        return@launch
                    }.id

            runCatchingApi {
                kassenbuchApiClient.createEntry(request.copy(documentId = uploadedDocumentId))
            }.onSuccess { outcome ->
                when (outcome) {
                    is KassenbuchCreateOutcome.Created -> {
                        logger.i { "KassenbuchViewModel.createEntry: entry created successfully" }
                        // The entry lands in the book the request named (null = the fiscal
                        // Ladenkasse, which is what the till always sends). Show that book, or a
                        // Tresor view would silently not list the entry just booked.
                        _kassenbuchState.update {
                            it.copy(
                                showCreateDialog = false,
                                isCreating = false,
                                selectedCashBookId = bookedCashBookId(request, it),
                            )
                        }
                        loadKassenbuchData()
                        viewModelScope.launch {
                            eventBus.emit(FinancialDocumentsEvent.KassenbuchEntryCreated)
                        }
                    }
                    // Nothing is booked yet: no reload, and the screen says it is waiting.
                    KassenbuchCreateOutcome.AwaitingApproval -> {
                        logger.i { "KassenbuchViewModel.createEntry: late booking sent to a manager for approval" }
                        _kassenbuchState.update {
                            it.copy(showCreateDialog = false, isCreating = false, lateBookingAwaitingApproval = true)
                        }
                    }
                }
            }.onError { uiError ->
                _kassenbuchState.update { it.copy(isCreating = false, error = uiError) }
            }
        }
    }

    /**
     * Export current kassenbuch entries to DATEV-compliant CSV format.
     *
     * Requests the server to generate a CSV file for the currently selected
     * date range. The server handles all DATEV formatting and returns the
     * CSV file ready for download.
     *
     * The CSV includes all DATEV-required fields formatted according
     * to the DATEV Kassenbuch specification:
     * - Semicolon-separated values
     * - Comma as decimal separator
     * - DD.MM.YYYY date format
     * - UTF-8 encoding
     *
     * @param onCsvGenerated Callback invoked with CSV file data and filename
     *                       when export is ready for download
     */

    /**
     * The server's canonical balance ("5524.72") as the card's display text ("5.524,72"). The card
     * used to print the server's German display string as it came; the server sends a decimal now
     * (admin16 NOTE-5), and the API client normalizes an older server's display form to one.
     */
    private fun balanceForDisplay(canonical: String): String = formatMoneyForDisplay(canonical, "")

    /**
     * Compute total income and expenses from entries.
     *
     * Sums the server's exact decimal `totalAmount` strings in [Decimal] — never via cents or
     * Double — grouped by entry type direction (isIncoming). Unparseable amounts are skipped
     * rather than silently counted as zero-with-a-crash.
     *
     * @return Pair of (formattedIncome, formattedExpenses) in German number format
     */
    private fun computeKpiSums(entries: List<KassenbuchDto.KassenbuchGroupedSummaryDto>): Pair<String, String> {
        var income = Decimal.ZERO
        var expenses = Decimal.ZERO

        for (entry in entries) {
            val amount = Decimal.parseOrNull(entry.totalAmount)?.abs() ?: continue

            if (entry.entryType.isIncoming) {
                income += amount
            } else {
                expenses += amount
            }
        }

        // Signed here, not by the card: a hardcoded "-" prefix printed "Ausgaben -0,00 €" for a book
        // with no outgoing entries (emulator pass 4, NOTE-3). The formatter leaves a zero unsigned.
        return Pair(
            formatMoneyForDisplay(income.toPlainString(), "", forceSign = true),
            formatMoneyForDisplay((-expenses).toPlainString(), "", forceSign = true),
        )
    }

    /**
     * Clear the current error state.
     */
    fun clearLateBookingAwaitingApproval() {
        _kassenbuchState.update { it.copy(lateBookingAwaitingApproval = false) }
    }

    fun clearError() {
        _kassenbuchState.update { it.copy(error = null) }
    }
}
