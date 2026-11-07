package org.example.project.admin.features.documents.presentation

import org.example.project.core.domain.enums.document.ProcessingStatus

/**
 * One applied panel filter of the Belege archive: what a removable chip shows on wider windows and
 * what the Filter button's badge counts. Search, period and (on wider windows) the type quick chips
 * are not panel filters, so they never appear here.
 */
sealed interface DocumentAppliedFilter {
    data class Advanced(
        val field: DocumentSearchFilter.AdvancedField,
        val value: String,
    ) : DocumentAppliedFilter

    data class Status(
        val status: ProcessingStatus,
    ) : DocumentAppliedFilter

    data object IncludeInactive : DocumentAppliedFilter
}
