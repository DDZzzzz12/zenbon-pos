/**
 * Grid-based coordinate system configuration for floor plan editor.
 *
 * Provides a fixed-size grid (80x50) that scales responsively to screen size.
 * Tables and room elements are positioned using integer grid coordinates,
 * which are converted to pixel positions at runtime based on screen dimensions.
 *
 * Benefits of grid-based system:
 * - Simple integer coordinates (no float precision issues)
 * - Natural snapping behavior
 * - Consistent across different screen sizes
 * - Easy to understand and debug (e.g., "table at grid 10,5")
 * - Suitable for up to 150+ elements with comfortable spacing
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.utils

/**
 * Grid system defaults and configuration constants.
 *
 * The grid is designed with an 80x50 layout (4000 total cells):
 * - 80 columns (horizontal) - matches 16:10 aspect ratio
 * - 50 rows (vertical)
 * - Aspect ratio: 1.6:1 (similar to common desktop screens)
 * - Capacity: Comfortably holds 150+ elements with spacing
 */
object GridDefaults {
    /**
     * Number of columns in the grid (horizontal).
     *
     * 80 columns provides good horizontal resolution for wide layouts
     * while maintaining reasonable cell sizes on common screen resolutions.
     */
    const val GRID_COLUMNS = 80

    /**
     * Number of rows in the grid (vertical).
     *
     * 50 rows provides vertical space for complex layouts with
     * multiple table arrangements and room elements.
     */
    const val GRID_ROWS = 55

    /**
     * Total number of cells in the grid.
     *
     * Calculated: 80 × 50 = 4000 cells
     */
    const val TOTAL_CELLS = GRID_COLUMNS * GRID_ROWS

    /**
     * Grid aspect ratio (width:height).
     *
     * 80:50 = 1.6:1 (equivalent to 16:10)
     * Matches common desktop and laptop screen ratios.
     */
    const val ASPECT_RATIO = GRID_COLUMNS.toFloat() / GRID_ROWS.toFloat()

    /**
     * Standard table sizes in grid cells.
     *
     * Provides common table dimensions for quick creation.
     */
    object TableSizes {
        /** Small circular table (2-person): 2×2 grid cells */
        const val SMALL_CIRCLE = 2

        /** Medium circular table (4-person): 3×3 grid cells */
        const val MEDIUM_CIRCLE = 3

        /** Large circular table (6-person): 4×4 grid cells */
        const val LARGE_CIRCLE = 4

        /** Small rectangular table (2-person): 2×3 grid cells */
        const val SMALL_RECT_WIDTH = 2
        const val SMALL_RECT_HEIGHT = 3

        /** Medium rectangular table (4-person): 3×4 grid cells */
        const val MEDIUM_RECT_WIDTH = 3
        const val MEDIUM_RECT_HEIGHT = 4

        /** Large rectangular table (6-8 person): 4×6 grid cells */
        const val LARGE_RECT_WIDTH = 4
        const val LARGE_RECT_HEIGHT = 6

        /** Extra large rectangular table (8-10 person): 5×8 grid cells */
        const val XLARGE_RECT_WIDTH = 5
        const val XLARGE_RECT_HEIGHT = 8
    }

    /**
     * Standard room element sizes in grid cells.
     */
    object ElementSizes {
        /** Standard wall thickness: 1 grid cell */
        const val WALL_THICKNESS = 1

        /** Standard door width: 2 grid cells */
        const val DOOR_WIDTH = 2

        /** Standard window width: 3 grid cells */
        const val WINDOW_WIDTH = 3

        /** Counter/bar dimensions: 8×2 grid cells */
        const val COUNTER_WIDTH = 8
        const val COUNTER_HEIGHT = 2
    }

    /**
     * Grid visualization settings.
     */
    object Visualization {
        /** Minimum grid cell size in pixels for visibility */
        const val MIN_CELL_SIZE_PX = 10f

        /** Maximum grid cell size in pixels to prevent oversized elements */
        const val MAX_CELL_SIZE_PX = 100f

        /** Grid line color alpha (0.0 - 1.0) */
        const val GRID_LINE_ALPHA = 0.2f

        /** Grid line stroke width in pixels */
        const val GRID_LINE_STROKE_WIDTH = 1f
    }
}
