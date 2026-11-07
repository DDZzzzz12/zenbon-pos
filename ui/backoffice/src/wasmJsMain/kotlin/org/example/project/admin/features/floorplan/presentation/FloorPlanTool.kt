package org.example.project.admin.features.floorplan.presentation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.Grid4x4
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.TableBar
import androidx.compose.material.icons.filled.Window
import androidx.compose.ui.graphics.vector.ImageVector
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.utils.GridDefaults

/**
 * The five things the palette adds. A table opens a number/seats dialog; the others are placed at once
 * on the plan's first free cell with their default footprint ([elementType] is the name the server
 * stores, [gridWidth] × [gridHeight] the size they start with), for the operator to drag into place.
 */
enum class FloorPlanTool(
    val icon: ImageVector,
    val labelKey: StringKey,
    val elementType: String?,
    val gridWidth: Int,
    val gridHeight: Int,
) {
    TABLE(Icons.Default.TableBar, AdminStringKeys.FloorPlanToolTable, null, 2, 2),
    WALL(Icons.Default.Grid4x4, AdminStringKeys.FloorPlanElementWall, "WALL", 8, GridDefaults.ElementSizes.WALL_THICKNESS),
    DOOR(Icons.Default.DoorFront, AdminStringKeys.FloorPlanElementDoor, "DOOR", GridDefaults.ElementSizes.DOOR_WIDTH, 1),
    WINDOW(Icons.Default.Window, AdminStringKeys.FloorPlanElementWindow, "WINDOW", GridDefaults.ElementSizes.WINDOW_WIDTH, 1),
    COUNTER(
        Icons.Default.Storefront,
        AdminStringKeys.FloorPlanElementCounter,
        "COUNTER",
        GridDefaults.ElementSizes.COUNTER_WIDTH,
        GridDefaults.ElementSizes.COUNTER_HEIGHT,
    ),
}
