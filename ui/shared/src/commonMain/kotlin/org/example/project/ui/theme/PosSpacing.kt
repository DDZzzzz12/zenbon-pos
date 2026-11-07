package org.example.project.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Zenbon spacing scale — fixed steps on a 4dp rhythm, the same on every screen size.
 *
 * Use these for spacing *inside* a component (padding, icon-to-label, row gaps). A button or a card
 * keeps its inner spacing whether it sits on a phone or a desktop; what changes with the window is
 * the layout around components, which comes from [LocalPosLayout] (screen padding, pane and grid
 * gaps, max content width).
 */
object PosSpacing {
    val xs: Dp = 2.dp
    val sm: Dp = 4.dp
    val md: Dp = 8.dp
    val lg: Dp = 12.dp
    val xl: Dp = 16.dp
    val xxl: Dp = 24.dp
    val xxxl: Dp = 32.dp
    val xxxxl: Dp = 48.dp
}
