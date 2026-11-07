package org.example.project.admin.core.presentation.components

/** A list's sort as the panel edits it: the screen's own column [key] and the direction. */
data class ListSortSelection(
    val key: Int,
    val ascending: Boolean,
)

/**
 * The filter panel draft of a list screen whose only filter besides search is one server-side yes/no
 * toggle that defaults to off (Lieferanten "Zu bestätigen", Debitoren "Nur aktive", Verträge "Inaktive
 * einblenden"), optionally with a sort ([sort], null where the screen has no panel sort). See
 * [ListToggleFilterBar].
 *
 * On a phone the toggle and the sort are panel fields, applied only by the panel's button; wider
 * windows show the toggle as a quick chip in the bar and sort by the table headers, so they have no
 * panel. A resize that widens the window while the panel is open keeps a field the draft already
 * changed ([showsToggle], [showsSort]) so the edit is never silently dropped.
 */
data class ListTogglePanelFilter(
    val on: Boolean,
    val sort: ListSortSelection? = null,
) {
    /** The Filter button's badge: the non-default (on) toggle, and a sort other than [defaultSort]. */
    fun activeCount(defaultSort: ListSortSelection? = null): Int = (if (on) 1 else 0) + (if (sort != null && sort != defaultSort) 1 else 0)

    /** The toggle is a panel field on a phone, or once this draft changed it. */
    fun showsToggle(
        compact: Boolean,
        applied: ListTogglePanelFilter,
    ): Boolean = compact || on != applied.on

    /** The sort is a panel field on a phone, or once this draft changed it; never where the screen has none. */
    fun showsSort(
        compact: Boolean,
        applied: ListTogglePanelFilter,
    ): Boolean = sort != null && (compact || sort != applied.sort)

    /** Nothing to edit: an apply button would do nothing, so the panel closes itself. */
    fun isEmptyPanel(
        compact: Boolean,
        applied: ListTogglePanelFilter,
    ): Boolean = !showsToggle(compact, applied) && !showsSort(compact, applied)

    /** The value the apply button must set, or null when the draft left it as applied. */
    fun toApply(applied: ListTogglePanelFilter): Boolean? = on.takeIf { it != applied.on }

    /** The sort the apply button must set, or null when the draft left it as applied (or there is none). */
    fun sortToApply(applied: ListTogglePanelFilter): ListSortSelection? = sort?.takeIf { it != applied.sort }

    /** The panel's reset: toggle off, sort back to [defaultSort]. */
    fun cleared(defaultSort: ListSortSelection? = null): ListTogglePanelFilter = ListTogglePanelFilter(on = false, sort = defaultSort)
}
