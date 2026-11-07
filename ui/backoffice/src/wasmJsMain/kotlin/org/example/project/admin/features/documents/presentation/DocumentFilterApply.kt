package org.example.project.admin.features.documents.presentation

import org.example.project.features.reports.presentation.ReportsGranularity

/**
 * Hands the Belege filter panel's draft [target] to the view model through its existing API: only
 * what differs from the applied filter is sent, quick changes first and the advanced fields last, so
 * the final search runs with the whole target. The search term is never touched — it is the bar's,
 * not the panel's. An unchanged draft sends no request at all.
 */
fun DocumentsViewModel.applyPanelFilter(target: DocumentSearchFilter) {
    val applied = state.applied
    applyPeriod(applied, target)
    if (target.fileType != applied.fileType) selectType(target.fileType)
    if (target.includeInactive != applied.includeInactive) toggleIncludeInactive()
    if (advancedDiffers(applied, target)) applyAdvanced(target)
}

private fun DocumentsViewModel.applyPeriod(
    applied: DocumentSearchFilter,
    target: DocumentSearchFilter,
) {
    val from = target.customFrom
    val to = target.customTo
    if (target.granularity == ReportsGranularity.CUSTOM && from != null && to != null) {
        val sameRange = applied.granularity == ReportsGranularity.CUSTOM && applied.customFrom == from && applied.customTo == to
        if (!sameRange) selectCustomRange(from, to)
        return
    }
    // Includes the CUSTOM segment before a range is picked: like the bar's segment, "no date filter".
    if (target.granularity != applied.granularity || target.anchor != applied.anchor) {
        selectPeriod(target.granularity, target.anchor)
    }
}

private fun advancedDiffers(
    applied: DocumentSearchFilter,
    target: DocumentSearchFilter,
): Boolean =
    applied.processingStatuses != target.processingStatuses ||
        DocumentSearchFilter.AdvancedField.entries.any { applied.fieldValue(it) != target.fieldValue(it) }

/** Writes every advanced field and status into the view model's own draft, then applies it. */
private fun DocumentsViewModel.applyAdvanced(target: DocumentSearchFilter) {
    DocumentSearchFilter.AdvancedField.entries.forEach { field -> updateDraft(field, target.fieldValue(field).orEmpty()) }
    val current = state.draft.processingStatuses
    val toggles = (current - target.processingStatuses) + (target.processingStatuses - current)
    toggles.sortedBy { it.ordinal }.forEach(::toggleDraftStatus)
    applyDraft()
}
