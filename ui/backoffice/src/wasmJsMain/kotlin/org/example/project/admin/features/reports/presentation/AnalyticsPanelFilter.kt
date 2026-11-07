package org.example.project.admin.features.reports.presentation

import kotlinx.datetime.LocalDate
import org.example.project.features.reports.presentation.ReportsGranularity

/**
 * What the Analysen filter panel edits, held as a draft until its apply button: the employee and the
 * Vergleich toggle on every width, and the [granularity] on a phone only (wider, the full period
 * stepper in the bar has it). A granularity the draft already changed stays in the panel when a
 * resize widens the window ([showsGranularity]), so the edit is never silently dropped.
 *
 * Applying goes through [needsReload] and [compareToSet] rather than one `load(...)`: the view model
 * keeps its compare flag only through `setCompareEnabled`, so a target that flips compare is loaded
 * with the target compare value first and then flips the flag, whose own reload asks for the very
 * same data (no stale response can win).
 */
data class AnalyticsPanelFilter(
    val granularity: ReportsGranularity,
    val employeeId: String?,
    val compareEnabled: Boolean,
) {
    /** The badge count: employee and Vergleich when set. The period is never counted (always set, in the bar). */
    fun activeCount(): Int = (if (employeeId != null) 1 else 0) + (if (compareEnabled) 1 else 0)

    /** The granularity is a panel field on a phone, or once this draft changed it. */
    fun showsGranularity(
        compact: Boolean,
        applied: AnalyticsPanelFilter,
    ): Boolean = compact || granularity != applied.granularity

    /** The panel's reset and the bar's clear-all: no employee, no comparison; the period stays. */
    fun cleared(): AnalyticsPanelFilter = copy(employeeId = null, compareEnabled = false)

    /** Whether applying this target needs a `load` (period granularity or employee changed). */
    fun needsReload(applied: AnalyticsPanelFilter): Boolean = granularity != applied.granularity || employeeId != applied.employeeId

    /** The compare value to hand to `setCompareEnabled`, or null when the target leaves it as applied. */
    fun compareToSet(applied: AnalyticsPanelFilter): Boolean? = compareEnabled.takeIf { it != applied.compareEnabled }

    /**
     * The anchor to load: a switched granularity keeps the date but clamps it to [today], as the
     * stepper's own granularity switch does, so the new period is never in the future.
     */
    fun anchorFor(
        applied: AnalyticsPanelFilter,
        anchor: LocalDate,
        today: LocalDate,
    ): LocalDate = if (granularity != applied.granularity) minOf(anchor, today) else anchor

    companion object {
        fun of(state: ReportsState): AnalyticsPanelFilter = AnalyticsPanelFilter(state.granularity, state.employeeId, state.compareEnabled)
    }
}
