package org.example.project.admin.features.floorplan.presentation

import org.example.project.core.data.models.RTable
import org.example.project.core.data.models.RoomElement
import org.example.project.core.presentation.error.UiError

/** Identifies a selected item without unifying the two models. */
sealed interface FloorPlanSelection {
    data class Table(
        val id: Long,
    ) : FloorPlanSelection

    data class Element(
        val id: Long,
    ) : FloorPlanSelection
}

/**
 * Editor state for one location's floor plan.
 *
 * [tables] and [elements] hold the *live* (possibly dragged-but-unsaved) grid positions.
 * [dirtyTableIds]/[dirtyElementIds] record which ids moved since the last load/save, so Save writes
 * only what changed — one batch for tables, one position write per moved element.
 */
data class FloorPlanEditorState(
    val locationTypeId: Long? = null,
    val tables: List<RTable> = emptyList(),
    val elements: List<RoomElement> = emptyList(),
    val selection: FloorPlanSelection? = null,
    val dirtyTableIds: Set<Long> = emptySet(),
    val dirtyElementIds: Set<Long> = emptySet(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: UiError? = null,
) {
    val hasUnsavedChanges: Boolean get() = dirtyTableIds.isNotEmpty() || dirtyElementIds.isNotEmpty()
}
