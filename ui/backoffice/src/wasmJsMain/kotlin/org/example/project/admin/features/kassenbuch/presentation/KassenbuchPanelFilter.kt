package org.example.project.admin.features.kassenbuch.presentation

import org.example.project.features.reports.presentation.ReportsGranularity

/**
 * What the Kassenbuch filter panel edits, held as a draft until its apply button. [granularity] is a
 * panel field on a phone only (wider, the full period stepper in the bar has it) and [searchTerm]
 * wherever the bar has no room for the search field ([KassenbuchBarLayout.searchInPanel]).
 *
 * A window resize can move either field between the bar and the panel while the panel is open. A
 * field the draft already changed then stays in the panel ([showsSearch], [showsGranularity]) and
 * is applied wherever it now sits, so an edit is never silently dropped.
 */
data class KassenbuchPanelFilter(
    val granularity: ReportsGranularity,
    val searchTerm: String,
) {
    /**
     * The Filter button's badge. Only search counts, and only where it is a panel field: the period
     * is always set and always visible in the bar, and a search field in the bar shows itself.
     */
    fun activeCount(searchInPanel: Boolean): Int = if (searchInPanel && searchTerm.isNotBlank()) 1 else 0

    /** Search is a panel field where the bar has no room for it, or once this draft changed it. */
    fun showsSearch(
        searchInPanel: Boolean,
        applied: KassenbuchPanelFilter,
    ): Boolean = searchInPanel || searchTerm != applied.searchTerm

    /** The granularity is a panel field on a phone, or once this draft changed it. */
    fun showsGranularity(
        compact: Boolean,
        applied: KassenbuchPanelFilter,
    ): Boolean = compact || granularity != applied.granularity

    /** The granularity the apply button must load, or null when the draft left it as applied. */
    fun granularityToApply(applied: KassenbuchPanelFilter): ReportsGranularity? = granularity.takeIf { it != applied.granularity }

    /** The search term the apply button must send, or null when the draft left it as applied. */
    fun searchToApply(applied: KassenbuchPanelFilter): String? = searchTerm.takeIf { it != applied.searchTerm }

    /** The panel's reset: no search where the panel shows it ([clearSearch]); the period stays. */
    fun cleared(clearSearch: Boolean): KassenbuchPanelFilter = if (clearSearch) copy(searchTerm = "") else this

    companion object {
        fun of(state: KassenbuchState): KassenbuchPanelFilter = KassenbuchPanelFilter(state.granularity, state.filterText)
    }
}
