package org.example.project.admin.features.zBon.presentation

import org.example.project.features.reports.presentation.ReportsGranularity

/**
 * What the Z-Bon filter panel edits, held as a draft until its apply button. The only field is the
 * [granularity] (Tag / Woche / Monat / Jahr), and only on a phone: wider windows show it in the bar's
 * full period stepper, which is why the screen has no panel there at all.
 *
 * A window resize can move the granularity between the bar and the panel while the panel is open. A
 * granularity the draft already changed then stays in the panel ([showsGranularity]) and is applied
 * wherever it now sits, so an edit is never silently dropped.
 */
data class ZBonPanelFilter(
    val granularity: ReportsGranularity,
) {
    /** The Filter button's badge: the period is always set and always visible in the bar, so never counted. */
    fun activeCount(): Int = 0

    /** The granularity is a panel field on a phone, or once this draft changed it. */
    fun showsGranularity(
        compact: Boolean,
        applied: ZBonPanelFilter,
    ): Boolean = compact || granularity != applied.granularity

    /** The granularity the apply button must load, or null when the draft left it as applied. */
    fun granularityToApply(applied: ZBonPanelFilter): ReportsGranularity? = granularity.takeIf { it != applied.granularity }

    companion object {
        fun of(state: ZBonListState): ZBonPanelFilter = ZBonPanelFilter(state.granularity)
    }
}
