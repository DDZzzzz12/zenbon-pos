package org.example.project.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Fixed width of a [PosSideSheet] on windows wide enough to show it beside a page. */
val PosSideSheetWidth: Dp = 400.dp

/** Window width from which a detail pane may push the page aside instead of overlaying it. */
private val SideSheetPushMinWindow: Dp = 1100.dp

/** True when a side pane must be drawn as a modal [PosSideSheet] over the page (windows below 1100dp). */
fun posSideSheetOverlays(windowWidth: Dp): Boolean = windowWidth < SideSheetPushMinWindow
