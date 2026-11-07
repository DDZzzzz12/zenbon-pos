package org.example.project.features.dashboard.presentation.state

import org.example.project.core.data.models.RTable
import org.example.project.core.data.models.RoomElement
import org.example.project.core.presentation.error.UiError
import org.example.project.core.utils.GridCellDimensions

/**
 * UI state for the floor plan screen.
 *
 * Represents all the data and state needed to render and interact with
 * the floor plan view, including tables, room elements, viewport
 * transforms (zoom/pan) and combine-mode selection state.
 *
 * This state is immutable and managed by the FloorPlanViewModel, following
 * the unidirectional data flow pattern for Compose state management.
 *
 * **Grid-Based System**: Uses an 80x50 grid where elements are positioned
 * using integer grid coordinates. The `gridCellDimensions` are calculated dynamically
 * based on screen dimensions to enable responsive layout.
 *
 * @property isLoading Whether the floor plan data is currently loading
 * @property tables List of all restaurant tables with grid-based position data
 * @property roomElements List of structural elements (walls, doors, windows, etc.)
 * @property gridCellDimensions Dimensions of each grid cell in pixels (dynamically calculated from screen size)
 * @property zoomLevel Current zoom level (1.0 = 100%, 2.0 = 200%, etc.)
 * @property panOffsetX Horizontal pan offset in pixels
 * @property panOffsetY Vertical pan offset in pixels
 * @property error Error message to display to user, null if no error
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
data class FloorPlanUiState(
    val isLoading: Boolean = true,
    val tables: List<RTable> = emptyList(),
    val roomElements: List<RoomElement> = emptyList(),
    val gridCellDimensions: GridCellDimensions = GridCellDimensions(20f, 20f),
    val zoomLevel: Float = 1.0f,
    val panOffsetX: Float = 0f,
    val panOffsetY: Float = 0f,
    val error: UiError? = null,
    // Combine mode
    val isCombineMode: Boolean = false,
    val combineConnections: Map<Long, Set<Long>> = emptyMap(),
    val combiningFromTableId: Long? = null,
    val combineLineEndX: Float = 0f,
    val combineLineEndY: Float = 0f,
    val combineHoverTableId: Long? = null,
    val combineConnectionsModified: Boolean = false,
)
