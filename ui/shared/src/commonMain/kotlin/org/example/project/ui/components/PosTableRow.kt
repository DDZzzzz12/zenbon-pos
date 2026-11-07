package org.example.project.ui.components

/**
 * One row of a [PosDataTable]. [cells] should have one entry per column (ragged rows are
 * normalized defensively). [emphasized] renders a bold row with a strong top divider — used for a
 * Summe/total row.
 *
 * When [onClick] is set the entire row is clickable; the caller captures row-specific data
 * (e.g. a header ID) in the closure so no generic type parameter is needed.
 */
data class PosTableRow(
    val cells: List<String>,
    val emphasized: Boolean = false,
    val onClick: (() -> Unit)? = null,
)
