package org.example.project.core.utils

/**
 * Absolute pixel rect of one grid-positioned floor-plan item, in plan space (before pan/zoom).
 *
 * @property x Left edge in pixels
 * @property y Top edge in pixels
 * @property width Width in pixels
 * @property height Height in pixels
 */
data class FloorPlanCellBounds(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)
