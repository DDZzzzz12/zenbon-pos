package org.example.project.admin.features.documents.presentation

import kotlinx.datetime.LocalDate
import org.example.project.core.domain.enums.document.ProcessingStatus
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.utils.EMPTY_VALUE
import org.example.project.features.reports.presentation.ReportsGranularity
import org.example.project.features.reports.presentation.calendarBounds
import org.example.project.features.reports.presentation.formatAnchor

/**
 * Every filter the document archive can apply, mapping one-to-one onto
 * [org.example.project.core.data.remote.DocumentManagementApiClient.searchDocuments].
 *
 * Amounts are held as [String] and never parsed to a `Double` — the wire contract is already
 * decimal strings, and the money rules forbid a floating-point hop.
 */
data class DocumentSearchFilter(
    val anchor: LocalDate,
    val granularity: ReportsGranularity = ReportsGranularity.MONTH,
    val customFrom: LocalDate? = null,
    val customTo: LocalDate? = null,
    val searchTerm: String = "",
    val fileType: String? = null,
    val vendorName: String? = null,
    val invoiceNumber: String? = null,
    val minAmount: String? = null,
    val maxAmount: String? = null,
    val bankName: String? = null,
    val statementNumber: String? = null,
    val uploadedBy: String? = null,
    /**
     * The workflow states to narrow to (E2E 16.9, e.g. "Geprüft"). Several are OR-ed; empty means no
     * status filter. Edited in the advanced panel alongside the seven text fields, so it follows the
     * same draft/apply rhythm and appears in the chip row — one chip per status.
     */
    val processingStatuses: Set<ProcessingStatus> = emptySet(),
    val includeInactive: Boolean = false,
) {
    /** The seven fields the advanced panel owns. Order here is the chip render order. */
    enum class AdvancedField {
        VENDOR_NAME,
        INVOICE_NUMBER,
        MIN_AMOUNT,
        MAX_AMOUNT,
        BANK_NAME,
        STATEMENT_NUMBER,
        UPLOADED_BY,
    }

    /**
     * Whether anything is narrowing the search — which decides whether an empty result offers a way
     * out or simply says the archive is empty.
     *
     * The period is deliberately **not** counted: it always has a value, so counting it would make
     * every search look filtered and the reset action would never be off.
     */
    val hasAny: Boolean
        get() =
            searchTerm.isNotBlank() ||
                fileType != null ||
                includeInactive ||
                processingStatuses.isNotEmpty() ||
                AdvancedField.entries.any { fieldValue(it) != null }

    fun fieldValue(field: AdvancedField): String? =
        when (field) {
            AdvancedField.VENDOR_NAME -> vendorName
            AdvancedField.INVOICE_NUMBER -> invoiceNumber
            AdvancedField.MIN_AMOUNT -> minAmount
            AdvancedField.MAX_AMOUNT -> maxAmount
            AdvancedField.BANK_NAME -> bankName
            AdvancedField.STATEMENT_NUMBER -> statementNumber
            AdvancedField.UPLOADED_BY -> uploadedBy
        }

    fun withAdvanced(
        field: AdvancedField,
        value: String?,
    ): DocumentSearchFilter {
        val clean = value?.takeIf { it.isNotBlank() }
        return when (field) {
            AdvancedField.VENDOR_NAME -> copy(vendorName = clean)
            AdvancedField.INVOICE_NUMBER -> copy(invoiceNumber = clean)
            AdvancedField.MIN_AMOUNT -> copy(minAmount = clean)
            AdvancedField.MAX_AMOUNT -> copy(maxAmount = clean)
            AdvancedField.BANK_NAME -> copy(bankName = clean)
            AdvancedField.STATEMENT_NUMBER -> copy(statementNumber = clean)
            AdvancedField.UPLOADED_BY -> copy(uploadedBy = clean)
        }
    }

    fun clear(field: AdvancedField): DocumentSearchFilter = withAdvanced(field, null)

    fun clearAllAdvanced(): DocumentSearchFilter = AdvancedField.entries.fold(copy(processingStatuses = emptySet())) { acc, field -> acc.clear(field) }

    fun withStatusToggled(status: ProcessingStatus): DocumentSearchFilter =
        copy(processingStatuses = if (status in processingStatuses) processingStatuses - status else processingStatuses + status)

    fun withoutStatus(status: ProcessingStatus): DocumentSearchFilter = copy(processingStatuses = processingStatuses - status)

    /** What the request sends: null for "no filter", else the statuses in a stable (declaration) order. */
    fun statusesForRequest(): List<ProcessingStatus>? = processingStatuses.sortedBy { it.ordinal }.takeIf { it.isNotEmpty() }

    companion object {
        /**
         * The statuses the panel offers. `PENDING`/`PROCESSING` are vestigial — nothing writes them
         * since the LLM extraction was removed — so a chip for them would always find nothing.
         */
        val FILTERABLE_STATUSES: List<ProcessingStatus> =
            listOf(
                ProcessingStatus.NONE,
                ProcessingStatus.COMPLETED,
                ProcessingStatus.IN_PROGRESS,
                ProcessingStatus.REVIEWED,
                ProcessingStatus.UNPROCESSABLE,
                ProcessingStatus.FAILED,
            )
    }
}

/**
 * The `startDate`/`endDate` pair to send, or `null` for "no date filter at all" — which is what an
 * empty [ReportsGranularity.CUSTOM] range means. The documents endpoint parses date-only bounds, so
 * only the calendar bounds are sent — never a time component, and never `now`: an archive is not a
 * report, and a document dated later today must not fall outside this month. The server filters by
 * the document date (the "Datum" column), falling back to the upload date for undated documents.
 */
fun DocumentSearchFilter.dateBounds(): Pair<String, String>? {
    if (granularity == ReportsGranularity.CUSTOM) {
        val from = customFrom ?: return null
        val to = customTo ?: return null
        return from.toString() to to.toString()
    }
    val (start, end) = granularity.calendarBounds(anchor)
    return start.toString() to end.toString()
}

/** The populated advanced fields, in [DocumentSearchFilter.AdvancedField] order, for the chip row. */
fun DocumentSearchFilter.activeAdvancedFilters(): List<Pair<DocumentSearchFilter.AdvancedField, String>> =
    DocumentSearchFilter.AdvancedField.entries.mapNotNull { field ->
        fieldValue(field)?.takeIf { it.isNotBlank() }?.let { field to it }
    }

/**
 * The period as the stepper and the phone result line show it: the anchored period in [language],
 * or the custom `from – to` range ([EMPTY_VALUE] while the Zeitraum segment has no range picked yet).
 */
fun DocumentSearchFilter.periodLabel(language: Language): String =
    if (granularity == ReportsGranularity.CUSTOM) {
        val from = customFrom
        val to = customTo
        if (from == null || to == null) EMPTY_VALUE else "$from – $to"
    } else {
        granularity.formatAnchor(anchor, language)
    }
