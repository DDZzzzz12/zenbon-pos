package org.example.project.ui.components

/**
 * Where a [PosDropdown] sits relative to the control that opened it.
 *
 * The names read anchor-edge first, then alignment: [BottomStart] hangs below with the left edges
 * flush, [EndTop] sits to the right with the top edges flush, [TopCenter] sits above with the two
 * centres on one vertical line. Each one **flips to its opposite**
 * rather than being clipped when the window has no room — a menu on the last row of a table opens
 * upward, a rail flyout near the right edge opens leftward.
 */
enum class PosDropdownAnchor {
    /** Below, left edges aligned. The default for a menu hanging off a button. */
    BottomStart,

    /** Below, right edges aligned. For overflow menus pinned to the right of a row. */
    BottomEnd,

    /** Above, left edges aligned. */
    TopStart,

    /** Above, right edges aligned. */
    TopEnd,

    /** Below, centred on the control. */
    BottomCenter,

    /** Above, centred on the control. What a bottom-bar item wants. */
    TopCenter,

    /** To the right, top edges aligned. What a collapsed navigation rail wants. */
    EndTop,

    /** To the left, top edges aligned. */
    StartTop,
}
