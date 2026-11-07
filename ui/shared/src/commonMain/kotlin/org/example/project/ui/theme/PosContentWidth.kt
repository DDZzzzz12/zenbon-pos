package org.example.project.ui.theme

import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * Caps forms, detail pages and settings sections at the window's [PosLayout.maxContentWidth]
 * (960dp expanded, 1280dp large, uncapped below). Tables and grids should not use it.
 *
 * Apply it **before** any `fillMaxWidth()`: a fill earlier in the chain sets a minimum width that a
 * later `widthIn(max = …)` cannot shrink.
 */
@Composable
fun Modifier.posMaxContentWidth(): Modifier {
    val max = LocalPosLayout.current.maxContentWidth
    return if (max == Dp.Unspecified) this else widthIn(max = max)
}
