package org.example.project.ui.components.table

/**
 * Inputs for [PosEditableDataTable]'s optional pagination footer. Page indices are zero-based.
 */
data class PosPaginationSpec(
    val currentPage: Int,
    val totalPages: Int,
    val totalFilteredCount: Int,
    val pageSize: Int,
    val currentPageItemCount: Int,
    val onPageChanged: (Int) -> Unit,
)

/**
 * The "start-end / total" range label for the pagination footer, or "0 / 0" when nothing matches.
 * Pure so it is unit-testable (the footer composable is not). Mirrors the pre-refactor logic:
 * start = currentPage*pageSize+1, end = currentPage*pageSize+currentPageItemCount.
 */
fun pageRangeLabel(
    currentPage: Int,
    pageSize: Int,
    currentPageItemCount: Int,
    totalFilteredCount: Int,
): String {
    if (totalFilteredCount <= 0) return "0 / 0"
    val start = currentPage * pageSize + 1
    val end = currentPage * pageSize + currentPageItemCount
    return "$start-$end / $totalFilteredCount"
}
