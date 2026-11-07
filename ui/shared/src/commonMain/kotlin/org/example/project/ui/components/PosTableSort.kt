package org.example.project.ui.components

/**
 * Which column a [PosContentTable] is ordered by, and in which direction.
 *
 * [columnIndex] indexes the column list the table was given, so the table needs no knowledge of the
 * caller's sort keys — the caller maps the index back onto its own enum.
 */
data class PosTableSort(
    val columnIndex: Int,
    val ascending: Boolean = true,
)
