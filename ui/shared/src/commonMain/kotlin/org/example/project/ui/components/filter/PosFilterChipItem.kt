package org.example.project.ui.components.filter

/**
 * One chip in [PosFilterBar]: a quick filter (toggled in place, [selected] shows its state) or an
 * applied panel filter (always shown as removable; [onClick] removes it).
 *
 * @property label already localized, e.g. "Rechnungen" or "Lieferant: Metro".
 */
data class PosFilterChipItem(
    val label: String,
    val selected: Boolean = false,
    val onClick: () -> Unit,
)
