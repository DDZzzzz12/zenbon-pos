package org.example.project.ui.screens.dashboard

import org.example.project.core.data.models.RTable

/**
 * Decides whether the tablet/desktop floor-plan screen shows the table grid instead of the canvas.
 *
 * Takeaway locations always use the grid. Any other location falls back to the grid once its
 * tables have loaded and none of them has a floor-plan position — a canvas with nothing drawn on it
 * would leave the operator without a way to open a table. While loading, the canvas keeps its
 * spinner so the screen does not flicker to the grid and back.
 */
fun shouldShowTableGrid(
    isTakeawayLocation: Boolean,
    isLoading: Boolean,
    tables: List<RTable>,
): Boolean {
    if (isTakeawayLocation) return true
    if (isLoading) return false
    return tables.none { it.hasPosition() }
}
