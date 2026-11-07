package org.example.project.ui.components.table

/** Active sort for a [PosEditableDataTable]: which column id is sorted, and the direction. */
data class PosSortState(
    val columnId: String,
    val ascending: Boolean,
)
