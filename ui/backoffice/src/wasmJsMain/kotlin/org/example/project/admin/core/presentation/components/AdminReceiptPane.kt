package org.example.project.admin.core.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val PANEL_WIDTH = 420.dp
private val PANEL_GAP = 16.dp

/**
 * 600 (PaneBreakpoints.PHONE_MAX) + 420 (panel) + 16 (gap) + slack. The list slot is `maxWidth - 436dp`,
 * so from here it stays at or above PHONE_MAX and a list that switches on PHONE_MAX (the daily report's
 * `compact`) cannot flip when a receipt opens.
 */
private val SIDE_BY_SIDE_MIN = 1040.dp

/**
 * A list with a receipt panel that opens for one of its rows.
 *
 * From 1040dp of content width the panel sits beside the list at a fixed 420dp; narrower, it covers the
 * list completely so no glyph is cut at an edge. The list stays composed either way, so its scroll
 * position and loaded pages survive opening and closing the panel. Widths are explicit rather than
 * `weight`, because the list's cards force `fillMaxWidth`.
 */
@Composable
fun AdminReceiptPane(
    panelOpen: Boolean,
    list: @Composable () -> Unit,
    panel: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sideBySide = maxWidth >= SIDE_BY_SIDE_MIN && panelOpen
        // One tree for both layouts, so the list keeps its state when the panel opens or the
        // window crosses the breakpoint. Only the modifiers differ.
        Box(
            if (sideBySide) {
                Modifier.width(maxWidth - PANEL_WIDTH - PANEL_GAP).fillMaxHeight()
            } else {
                Modifier.fillMaxSize()
            },
        ) { list() }
        if (panelOpen) {
            Surface(
                modifier =
                    if (sideBySide) {
                        Modifier.align(Alignment.TopEnd).width(PANEL_WIDTH).fillMaxHeight()
                    } else {
                        Modifier.fillMaxSize()
                    },
                tonalElevation = 6.dp,
            ) { panel() }
        }
    }
}
