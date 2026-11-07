package org.example.project.admin.features.documents.presentation

import kotlinx.datetime.LocalDate
import org.example.project.core.domain.dto.document.DocumentDto
import org.example.project.core.presentation.error.UiError

/**
 * [applied] is what the last request used and is the single source of truth for the result list,
 * the chip row and the advanced-filter badge — they cannot disagree because they all read it.
 * [draft] is what the advanced panel is editing and never reaches a request until Anwenden.
 */
data class DocumentsState(
    val applied: DocumentSearchFilter,
    val draft: DocumentSearchFilter,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val items: List<DocumentDto.DocumentListItemDto> = emptyList(),
    val total: Long = 0,
    val hasMore: Boolean = false,
    val availableTypes: List<String> = emptyList(),
    val advancedExpanded: Boolean = false,
    val error: UiError? = null,
) {
    companion object {
        fun initial(today: LocalDate): DocumentsState {
            val filter = DocumentSearchFilter(anchor = today)
            return DocumentsState(applied = filter, draft = filter)
        }
    }
}
