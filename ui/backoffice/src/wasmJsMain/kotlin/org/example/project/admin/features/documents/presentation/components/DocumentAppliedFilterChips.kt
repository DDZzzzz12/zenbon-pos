package org.example.project.admin.features.documents.presentation.components

import androidx.compose.runtime.Composable
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.documents.presentation.DocumentAppliedFilter
import org.example.project.admin.features.documents.presentation.DocumentSearchFilter
import org.example.project.admin.features.documents.presentation.activeAdvancedFilters
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.filter.PosFilterChipItem

/** The label key for each advanced field. Kept pure so a missing mapping is a test failure. */
fun advancedFieldLabelKey(field: DocumentSearchFilter.AdvancedField): StringKey =
    when (field) {
        DocumentSearchFilter.AdvancedField.VENDOR_NAME -> AdminStringKeys.DocumentsFieldVendor
        DocumentSearchFilter.AdvancedField.INVOICE_NUMBER -> AdminStringKeys.DocumentsFieldInvoiceNumber
        DocumentSearchFilter.AdvancedField.MIN_AMOUNT -> AdminStringKeys.DocumentsFieldMinAmount
        DocumentSearchFilter.AdvancedField.MAX_AMOUNT -> AdminStringKeys.DocumentsFieldMaxAmount
        DocumentSearchFilter.AdvancedField.BANK_NAME -> AdminStringKeys.DocumentsFieldBank
        DocumentSearchFilter.AdvancedField.STATEMENT_NUMBER -> AdminStringKeys.DocumentsFieldStatementNumber
        DocumentSearchFilter.AdvancedField.UPLOADED_BY -> AdminStringKeys.DocumentsFieldUploadedBy
    }

/**
 * Every applied panel filter, derived from the **applied** filter — so the chips and the Filter
 * badge always describe the results on screen, never an uncommitted panel draft. Advanced fields in
 * [DocumentSearchFilter.AdvancedField] order, then the statuses in declaration order, then
 * include-inactive. This is the single source of truth for "what is applied".
 */
fun documentAppliedFilters(applied: DocumentSearchFilter): List<DocumentAppliedFilter> =
    applied.activeAdvancedFilters().map { (field, value) -> DocumentAppliedFilter.Advanced(field, value) } +
        applied.processingStatuses.sortedBy { it.ordinal }.map { DocumentAppliedFilter.Status(it) } +
        listOfNotNull(DocumentAppliedFilter.IncludeInactive.takeIf { applied.includeInactive })

/** [documentAppliedFilters] as removable [PosFilterChipItem]s; [onRemove] drops one from the applied filter. */
@Composable
fun documentAppliedFilterChips(
    applied: DocumentSearchFilter,
    onRemove: (DocumentAppliedFilter) -> Unit,
): List<PosFilterChipItem> =
    documentAppliedFilters(applied).map { filter ->
        val label =
            when (filter) {
                is DocumentAppliedFilter.Advanced -> "${str(advancedFieldLabelKey(filter.field))}: ${filter.value}"
                is DocumentAppliedFilter.Status ->
                    "${str(AdminStringKeys.DocumentsFieldProcessingStatus)}: ${str(processingStatusLabelKey(filter.status))}"
                DocumentAppliedFilter.IncludeInactive -> str(AdminStringKeys.DocumentsIncludeInactive)
            }
        PosFilterChipItem(label = label, onClick = { onRemove(filter) })
    }
