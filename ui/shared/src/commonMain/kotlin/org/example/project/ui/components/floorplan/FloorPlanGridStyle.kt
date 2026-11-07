package org.example.project.ui.components.floorplan

/**
 * How the floor plan's background guides are drawn.
 *
 * Guides are a design aid, not content, so the two apps may differ here without the editor's
 * preview becoming untruthful: tables and elements render identically either way.
 */
enum class FloorPlanGridStyle {
    /** A dot at each cell corner. The till's look — quiet behind a live service screen. */
    Dots,

    /** Minor lines every cell, major lines every fifth. The editor's alignment aid. */
    Lines,
}
