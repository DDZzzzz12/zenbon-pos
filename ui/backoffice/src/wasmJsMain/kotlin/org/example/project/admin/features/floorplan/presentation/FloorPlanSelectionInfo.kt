package org.example.project.admin.features.floorplan.presentation

/**
 * What the selection panels show about the selected table or element, resolved from the editor state
 * by id. [column] and [row] are 1-based for people (the grid itself counts from 0); [minSide] is the
 * smallest cell count either side may take (a table 2, any other element 1).
 */
data class FloorPlanSelectionInfo(
    val isTable: Boolean,
    val tableId: Long?,
    val tableNumber: String?,
    val elementType: String?,
    val tableStatus: String?,
    val seats: Long?,
    val width: Int,
    val height: Int,
    val rotation: Float,
    val minSide: Int,
    val column: Int,
    val row: Int,
) {
    /** Equal sides draw a round table. */
    val isRound: Boolean get() = isTable && width == height

    val rotationDegrees: Int get() = rotation.toInt()
}

fun FloorPlanEditorState.selectionInfo(): FloorPlanSelectionInfo? =
    when (val sel = selection) {
        is FloorPlanSelection.Table ->
            tables.firstOrNull { it.id == sel.id }?.let { t ->
                FloorPlanSelectionInfo(
                    isTable = true,
                    tableId = t.id,
                    tableNumber = t.tableNumber,
                    elementType = null,
                    tableStatus = t.tableStatus,
                    seats = t.seats,
                    width = t.gridWidth ?: 2,
                    height = t.gridHeight ?: 2,
                    rotation = t.rotation ?: 0f,
                    minSide = 2,
                    column = (t.gridX ?: 0) + 1,
                    row = (t.gridY ?: 0) + 1,
                )
            }
        is FloorPlanSelection.Element ->
            elements.firstOrNull { it.id == sel.id }?.let { e ->
                FloorPlanSelectionInfo(
                    isTable = false,
                    tableId = null,
                    tableNumber = null,
                    elementType = e.elementType,
                    tableStatus = null,
                    seats = null,
                    width = e.gridWidth,
                    height = e.gridHeight,
                    rotation = e.rotation,
                    minSide = 1,
                    column = e.gridX + 1,
                    row = e.gridY + 1,
                )
            }
        null -> null
    }
