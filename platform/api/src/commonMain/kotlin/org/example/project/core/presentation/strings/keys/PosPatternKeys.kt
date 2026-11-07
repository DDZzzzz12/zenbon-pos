package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

/**
 * Strings of the shared UI patterns in shared-ui — filter bar and panel, form save bar, setting
 * rows. Screen-specific labels stay in their feature's keys; these are the pattern's own words.
 */
object PosPatternKeys {
    val filter = StringKey("pattern.filter")

    /** Accessible name of the icon-only filter button on phones. {0} = number of active filters. */
    val filterButtonDescription = StringKey("pattern.filter_button_description")

    /** Result-line suffix on phones, where applied-filter chips are hidden. {0} = count. */
    /** Accessible name of the section dropdown that replaces the pane chips on phones. */
    val paneSelectorDescription = StringKey("pattern.pane_selector_description")

    val filterActiveCount = StringKey("pattern.filter_active_count")
    val filterReset = StringKey("pattern.filter_reset")
    val filterClearAll = StringKey("pattern.filter_clear_all")
    val filterApply = StringKey("pattern.filter_apply")

    /** Primary button of the full-screen filter panel. {0} = live result count. */
    val filterShowResults = StringKey("pattern.filter_show_results")
    val filterNoResults = StringKey("pattern.filter_no_results")
    val filterRemoveDescription = StringKey("pattern.filter_remove_description")

    /** Caption of the period field in a filter panel ("Zeitraum"). */
    val filterPeriod = StringKey("pattern.filter_period")

    /** Caption of the sort-column picker in a filter panel ("Sortierung"). */
    val sortLabel = StringKey("pattern.sort_label")
    val sortAscending = StringKey("pattern.sort_ascending")
    val sortDescending = StringKey("pattern.sort_descending")

    /** Applied-sort chip on wide windows. {0} = the column, {1} = the direction. */
    val sortChip = StringKey("pattern.sort_chip")

    val formChanged = StringKey("pattern.form_changed")
    val formRequiredNote = StringKey("pattern.form_required_note")
    val formErrorCountOne = StringKey("pattern.form_error_count_one")

    /** {0} = number of fields with errors (2 or more). */
    val formErrorCountMany = StringKey("pattern.form_error_count_many")

    /** {0} = the localized name of the missing capability. */
    val settingsReadOnly = StringKey("pattern.settings_read_only")
    val settingsDangerZone = StringKey("pattern.settings_danger_zone")

    /** Accessible name of the collapsed KPI summary row's expand action. */
    val kpiSummaryExpand = StringKey("pattern.kpi_summary_expand")

    /** Accessible name of the expanded KPI summary row's collapse action. */
    val kpiSummaryCollapse = StringKey("pattern.kpi_summary_collapse")
}
