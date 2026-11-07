package org.example.project.admin.features.salesinvoices.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.example.project.admin.core.presentation.format.editableDecimalText
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.data.remote.DebtorApiClient
import org.example.project.core.data.remote.SalesInvoiceApiClient
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.utils.isBlankOrIsoDate
import org.example.project.core.utils.toCanonicalMoneyOrNull
import org.example.project.money.Decimal
import kotlin.time.Clock

/**
 * Drives the standalone-invoice editor: loads the active debtors (and, in edit mode, the draft),
 * tracks the typed form, validates before submit and saves via `createStandalone` / `updateStandalone`.
 *
 * The screen calls [load] from a `LaunchedEffect`, never the constructor: adminApp view models share
 * one ViewModelStoreOwner and survive logout, so an `init`-load would show the previous session's
 * invoice.
 *
 * Issuing is guarded at both levels: [issue] refuses a non-draft locally, the server answers 409
 * `SALES_INVOICE_IMMUTABLE` regardless (project rule 8).
 */
class SalesInvoiceEditorViewModel(
    private val api: SalesInvoiceApiClient,
    private val debtorApi: DebtorApiClient,
) : ViewModel() {
    private val log = Logger.withTag("salesInvoiceEditor")

    var state by mutableStateOf(SalesInvoiceEditorState())
        private set

    fun load(invoiceId: Long?) {
        state = SalesInvoiceEditorState(invoiceId = invoiceId, isLoading = true)
        viewModelScope.launch {
            runCatchingApi {
                val debtors = debtorApi.list(activeOnly = true)
                val invoice = invoiceId?.let { api.get(it) }
                debtors to invoice
            }.onSuccess { (debtors, invoice) ->
                val loaded = state.copy(debtors = debtors.items)
                state = if (invoice == null) loaded.copy(isLoading = false) else loaded.fromDto(invoice).copy(isLoading = false)
            }.onError(retry = { load(invoiceId) }) { uiError ->
                log.e { "editor load failed: $uiError" }
                state = state.copy(isLoading = false, error = uiError)
            }
        }
    }

    fun selectDebtor(id: Long) {
        val debtor = state.debtors.firstOrNull { it.id == id } ?: return
        update {
            it.copy(
                debtorId = id,
                buyerName = debtor.name,
                buyerVatId = debtor.vatId.orEmpty(),
                buyerStreet = debtor.street.orEmpty(),
                buyerZip = debtor.zip.orEmpty(),
                buyerCity = debtor.city.orEmpty(),
                buyerCountry = debtor.country.orEmpty(),
                buyerEmail = debtor.email.orEmpty(),
                dueDate = debtor.dueDateFromTerms() ?: it.dueDate,
                revenueAccountNo = debtor.defaultRevenueAccountNo.orEmpty(),
                buCode = debtor.defaultBuCode.orEmpty(),
            )
        }
    }

    fun onBuyerNameChange(value: String) = update { it.copy(buyerName = value) }

    fun onBuyerVatIdChange(value: String) = update { it.copy(buyerVatId = value) }

    fun onBuyerStreetChange(value: String) = update { it.copy(buyerStreet = value) }

    fun onBuyerZipChange(value: String) = update { it.copy(buyerZip = value) }

    fun onBuyerCityChange(value: String) = update { it.copy(buyerCity = value) }

    fun onBuyerCountryChange(value: String) = update { it.copy(buyerCountry = value) }

    fun onBuyerEmailChange(value: String) = update { it.copy(buyerEmail = value) }

    fun onServiceDateChange(value: String) = update { it.copy(serviceDate = value) }

    fun onDueDateChange(value: String) = update { it.copy(dueDate = value) }

    fun onPaymentTermsChange(value: String) = update { it.copy(paymentTermsText = value) }

    fun onRevenueAccountChange(value: String) = update { it.copy(revenueAccountNo = value) }

    fun onBuCodeChange(value: String) = update { it.copy(buCode = value) }

    fun onNotesChange(value: String) = update { it.copy(notes = value) }

    fun addLine() = update { it.copy(lines = it.lines + EditorLine()) }

    fun removeLine(index: Int) =
        update {
            if (it.lines.size <= 1) it else it.copy(lines = it.lines.filterIndexed { i, _ -> i != index })
        }

    fun updateLine(
        index: Int,
        transform: (EditorLine) -> EditorLine,
    ) = update { it.copy(lines = it.lines.mapIndexed { i, line -> if (i == index) transform(line) else line }) }

    fun clearError() {
        state = state.copy(error = null)
    }

    fun save() {
        if (state.isSaving) return
        if (state.invoiceId != null && state.status != "DRAFT") return
        if (state.invoiceId != null && state.debtorInactive) {
            log.w { "save withheld: the draft's debtor is deactivated" }
            return
        }
        val errors = validationErrors(state)
        if (errors.isNotEmpty()) {
            log.d { "sales invoice save refused locally: ${errors.keys}" }
            state = state.copy(fieldErrors = errors, validationAttempt = state.validationAttempt + 1)
            return
        }
        state = state.copy(isSaving = true, error = null)
        val id = state.invoiceId
        viewModelScope.launch {
            runCatchingApi {
                val request = state.toCreateRequest()
                if (id == null) {
                    api.createStandalone(request)
                } else {
                    api.updateStandalone(id, request.toUpdate())
                }
            }.onSuccess { updated ->
                log.d { "sales invoice ${updated.id} saved" }
                state =
                    state.copy(
                        invoiceId = updated.id,
                        status = updated.status,
                        invoiceNumber = updated.invoiceNumber.orEmpty(),
                        isSaving = false,
                        hasChanges = false,
                        saved = true,
                    )
            }.onError { uiError ->
                log.e { "sales invoice save failed: $uiError" }
                state = state.copy(isSaving = false, error = uiError)
            }
        }
    }

    /**
     * "Speichern und ausstellen": writes the draft (create, or replace) and then issues it, so the
     * operator does not have to save first. Validated exactly like [save] — nothing is sent for an
     * invalid form. If the write succeeds and the issue then fails, the draft stays saved (the form
     * shows it as such) and only the error is raised; issuing stays a separate, irreversible step on
     * the server.
     */

    /**
     * Runs the save validation without sending anything and reports whether the form may go on. The
     * screen calls it **before** it opens the irreversible "Rechnung ausstellen" confirm, so a form with
     * a missing field marks it and scrolls to it instead of asking a question that cannot be answered yet.
     */
    fun validateForIssue(): Boolean {
        if (!state.canSaveAndIssue) return false
        val errors = validationErrors(state)
        if (errors.isEmpty()) return true
        log.d { "issue refused before the confirm: ${errors.keys}" }
        state = state.copy(fieldErrors = errors, validationAttempt = state.validationAttempt + 1)
        return false
    }

    fun saveAndIssue() {
        if (!state.canSaveAndIssue) return
        val errors = validationErrors(state)
        if (errors.isNotEmpty()) {
            log.d { "save-and-issue refused locally: ${errors.keys}" }
            state = state.copy(fieldErrors = errors, validationAttempt = state.validationAttempt + 1)
            return
        }
        state = state.copy(isSaving = true, error = null)
        val id = state.invoiceId
        viewModelScope.launch {
            val request = state.toCreateRequest()
            val written = runCatchingApi { if (id == null) api.createStandalone(request) else api.updateStandalone(id, request.toUpdate()) }
            val draft = written.getOrNull()
            if (draft == null) {
                written.onError { uiError ->
                    log.e { "save-and-issue: the save failed: $uiError" }
                    state = state.copy(isSaving = false, error = uiError)
                }
                return@launch
            }
            state = state.copy(invoiceId = draft.id, status = draft.status, hasChanges = false, saved = true)
            runCatchingApi { api.issue(draft.id) }
                .onSuccess { issued ->
                    log.d { "saved and issued ${draft.id} as ${issued.invoiceNumber}" }
                    state = state.copy(isSaving = false, status = issued.status, invoiceNumber = issued.invoiceNumber.orEmpty(), isIssued = true)
                }.onError { uiError ->
                    log.e { "save-and-issue: issuing ${draft.id} failed: $uiError" }
                    state = state.copy(isSaving = false, error = uiError)
                }
        }
    }

    /** Irreversible — consumes the §14 number. The list screen's issue path is the same endpoint. */
    fun issue() {
        val id = state.invoiceId ?: return
        if (!state.canIssue) return
        state = state.copy(isSaving = true, error = null)
        viewModelScope.launch {
            runCatchingApi { api.issue(id) }
                .onSuccess { issued ->
                    log.d { "issued $id as ${issued.invoiceNumber}" }
                    state = state.copy(isSaving = false, status = issued.status, invoiceNumber = issued.invoiceNumber.orEmpty(), isIssued = true)
                }.onError { uiError ->
                    log.e { "issue failed for $id: $uiError" }
                    state = state.copy(isSaving = false, error = uiError)
                }
        }
    }

    /**
     * Every failed rule, keyed by the field it belongs to — the server's `validate()` rules plus the
     * two dates being dates (the date fields hand over the typed text when it is no date). The screen
     * marks each field and focuses the first in [SalesInvoiceEditorFields.order].
     */
    private fun validationErrors(state: SalesInvoiceEditorState): Map<String, StringKey> =
        buildMap {
            if (state.buyerName.isBlank()) put(SalesInvoiceEditorFields.BUYER_NAME, AdminStringKeys.SalesInvoiceEditorBuyerNameRequired)
            when {
                state.serviceDate.isBlank() -> put(SalesInvoiceEditorFields.SERVICE_DATE, AdminStringKeys.SalesInvoiceEditorServiceDateRequired)
                !isBlankOrIsoDate(state.serviceDate.substringBefore('T')) -> put(SalesInvoiceEditorFields.SERVICE_DATE, CommonKeys.dateInvalid)
            }
            if (!isBlankOrIsoDate(state.dueDate.substringBefore('T'))) put(SalesInvoiceEditorFields.DUE_DATE, CommonKeys.dateInvalid)
            state.lines.forEachIndexed { index, line ->
                if (line.itemName.isBlank()) put(SalesInvoiceEditorFields.itemName(index), AdminStringKeys.SalesInvoiceEditorLineItemNameRequired)
                val quantity = Decimal.parseOrNull(line.quantity.trim())
                if (quantity == null || quantity <= Decimal.ZERO) {
                    put(SalesInvoiceEditorFields.quantity(index), AdminStringKeys.SalesInvoiceEditorLineQuantityInvalid)
                }
                val price = line.grossUnitPrice.toCanonicalMoneyOrNull()?.let(Decimal::parseOrNull)
                if (price == null || price <= Decimal.ZERO) {
                    put(SalesInvoiceEditorFields.price(index), AdminStringKeys.SalesInvoiceEditorLinePriceInvalid)
                }
            }
        }

    private fun update(transform: (SalesInvoiceEditorState) -> SalesInvoiceEditorState) {
        if (state.readOnly) return
        val next = transform(state)
        state =
            next.copy(
                hasChanges = true,
                saved = false,
                // Only fields already flagged are re-checked: a fixed field loses its error as the
                // operator types, an untouched one does not light up before the next Save press.
                fieldErrors =
                    if (state.fieldErrors.isEmpty()) emptyMap() else validationErrors(next).filterKeys { it in state.fieldErrors },
            )
    }

    private fun SalesInvoiceEditorState.fromDto(dto: SalesInvoiceDto.ResponseDto): SalesInvoiceEditorState =
        copy(
            invoiceId = dto.id,
            status = dto.status,
            invoiceNumber = dto.invoiceNumber.orEmpty(),
            debtorId = dto.debtorId,
            buyerName = dto.buyerName,
            buyerVatId = dto.buyerVatId.orEmpty(),
            buyerStreet = dto.buyerStreet.orEmpty(),
            buyerZip = dto.buyerZip.orEmpty(),
            buyerCity = dto.buyerCity.orEmpty(),
            buyerCountry = dto.buyerCountry.orEmpty(),
            buyerEmail = dto.buyerEmail.orEmpty(),
            serviceDate = dto.serviceDate,
            dueDate = dto.dueDate.orEmpty(),
            paymentTermsText = dto.paymentTermsText.orEmpty(),
            revenueAccountNo = dto.revenueAccountNo.orEmpty(),
            buCode = dto.buCode.orEmpty(),
            notes = dto.notes.orEmpty(),
            currency = dto.currency,
            lines = dto.lines.map { it.toEditorLine() },
            hasChanges = false,
        )

    private fun SalesInvoiceDto.LineDto.toEditorLine(): EditorLine =
        EditorLine(
            itemName = itemName,
            itemDescription = itemDescription.orEmpty(),
            quantity = editableDecimalText(this.quantity, 0),
            unitCode = "C62",
            // The typed gross unit price is stored on the line and read back verbatim: re-deriving
            // `grossAmount / quantity` at scale 2 cannot round-trip a sub-cent unit price, so a
            // save-nothing reopen would silently change the total.
            grossUnitPrice = editableDecimalText(grossUnitPrice, 2),
            vatRate = vatRate,
        )

    private fun SalesInvoiceEditorState.toCreateRequest() =
        SalesInvoiceDto.CreateStandaloneRequestDto(
            debtorId = debtorId,
            buyerName = buyerName.trim(),
            buyerVatId = buyerVatId.blankToNull(),
            buyerStreet = buyerStreet.blankToNull(),
            buyerZip = buyerZip.blankToNull(),
            buyerCity = buyerCity.blankToNull(),
            buyerCountry = buyerCountry.blankToNull(),
            buyerEmail = buyerEmail.blankToNull(),
            serviceDate = serviceDate.trim(),
            dueDate = dueDate.blankToNull(),
            paymentTermsText = paymentTermsText.blankToNull(),
            revenueAccountNo = revenueAccountNo.blankToNull(),
            buCode = buCode.blankToNull(),
            notes = notes.blankToNull(),
            lines = toLineInputs(),
        )

    private fun SalesInvoiceEditorState.toLineInputs(): List<SalesInvoiceDto.StandaloneLineInputDto> =
        lines.map { line ->
            SalesInvoiceDto.StandaloneLineInputDto(
                itemName = line.itemName.trim(),
                itemDescription = line.itemDescription.blankToNull(),
                quantity = line.quantity.trim(),
                unitCode = line.unitCode.trim().ifBlank { "C62" },
                grossUnitPrice = line.grossUnitPrice.toCanonicalMoneyOrNull() ?: line.grossUnitPrice.trim(),
                vatRate = line.vatRate.trim(),
            )
        }
}

private fun SalesInvoiceDto.CreateStandaloneRequestDto.toUpdate() =
    SalesInvoiceDto.UpdateStandaloneRequestDto(
        debtorId = debtorId,
        buyerName = buyerName,
        buyerVatId = buyerVatId,
        buyerStreet = buyerStreet,
        buyerZip = buyerZip,
        buyerCity = buyerCity,
        buyerCountry = buyerCountry,
        buyerEmail = buyerEmail,
        serviceDate = serviceDate,
        dueDate = dueDate,
        paymentTermsText = paymentTermsText,
        revenueAccountNo = revenueAccountNo,
        buCode = buCode,
        notes = notes,
        lines = lines,
    )

private fun org.example.project.core.domain.dto.debtor.DebtorDto.ResponseDto.dueDateFromTerms(): String? {
    val days = paymentTermsDays ?: return null
    val due =
        Clock.System
            .now()
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date
            .plus(DatePeriod(days = days))
    return LocalDateTime(due, LocalTime(0, 0)).toString()
}

private fun String.blankToNull(): String? = trim().takeIf { it.isNotEmpty() }
