package org.example.project.admin.features.floorplan.presentation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How the Grundriss editor is arranged, from the width it really has: [WIDE] a palette column, the
 * canvas and a 300dp inspector side by side; [MEDIUM] the palette as a row above a 360dp canvas with a
 * selection bar under it; [COMPACT] five tiles, a 300dp canvas, the touch hint and a selection card.
 * [canvasHeight] is the fixed pane height of the stacked layouts: a selection panel that appeared under
 * a flexible canvas would resize it, and the plan's cell size derives from the pane.
 */
enum class FloorPlanLayout(
    val canvasHeight: Dp?,
) {
    WIDE(null),
    MEDIUM(360.dp),
    COMPACT(300.dp),
    ;

    companion object {
        /** Palette 176 + canvas ≥ 400 + inspector 300 plus gaps and page padding. */
        val WideMinWidth: Dp = 900.dp
        val MediumMinWidth: Dp = 560.dp

        fun forWidth(contentWidth: Dp): FloorPlanLayout =
            when {
                contentWidth >= WideMinWidth -> WIDE
                contentWidth >= MediumMinWidth -> MEDIUM
                else -> COMPACT
            }
    }
}
