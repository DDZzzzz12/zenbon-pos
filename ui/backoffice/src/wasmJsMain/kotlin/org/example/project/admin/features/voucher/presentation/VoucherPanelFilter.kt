package org.example.project.admin.features.voucher.presentation

import org.example.project.features.reports.presentation.ReportsGranularity

/**
 * What the Gutscheine filter panel edits, held as a draft until its apply button. [granularity] is a
 * panel field on a phone only and [searchTerm] wherever the bar has no room for search; otherwise
 * they stay in the bar and the panel edits [employeeId] alone.
 *
 * A window resize can move search or the granularity between the bar and the panel while the panel
 * is open. A field the draft has already changed then stays in the panel ([showsSearch],
 * [showsGranularity]) and is applied wherever it now sits ([searchToApply]), so an edit is never
 * silently dropped.
 */
data class VoucherPanelFilter(
    val granularity: ReportsGranularity,
    val employeeId: String?,
    val searchTerm: String,
) {
    /** Search is a panel field where the bar has no room for it, or once this draft changed it. */
    fun showsSearch(
        searchInPanel: Boolean,
        applied: VoucherPanelFilter,
    ): Boolean = searchInPanel || searchTerm != applied.searchTerm

    /** The granularity is a panel field on a phone, or once this draft changed it. */
    fun showsGranularity(
        compact: Boolean,
        applied: VoucherPanelFilter,
    ): Boolean = compact || granularity != applied.granularity

    /** The search term the apply button must send, or null when the draft left it as applied. */
    fun searchToApply(applied: VoucherPanelFilter): String? = searchTerm.takeIf { it != applied.searchTerm }

    /** The panel's reset: no employee, and no search where the panel shows it ([clearSearch]). */
    fun cleared(clearSearch: Boolean): VoucherPanelFilter = copy(employeeId = null, searchTerm = if (clearSearch) "" else searchTerm)

    companion object {
        fun of(state: VoucherUiState): VoucherPanelFilter = VoucherPanelFilter(state.granularity, state.employeeId, state.searchTerm)
    }
}
