package org.example.project.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.ui.layout.PaneBreakpoints

private val EstimatedCharWidth = 9.dp
private val ButtonChrome = 48.dp
private val FooterGap = 8.dp
private val PhoneCloseTarget = 48.dp
private val WideCloseTarget = 32.dp

/** Whether the two footer buttons no longer fit side by side and must stack (estimated from text length). */
fun posDialogFooterStacks(
    availableWidth: Dp,
    primaryText: String,
    secondaryText: String,
): Boolean {
    val needed = estimatedButtonWidth(primaryText) + estimatedButtonWidth(secondaryText) + FooterGap
    return needed > availableWidth
}

/** Touch target of the compact dialog's close button: 48dp on a phone, 32dp elsewhere. */
fun posDialogCloseTargetSize(windowWidth: Dp): Dp = if (windowWidth < PaneBreakpoints.PHONE_MAX) PhoneCloseTarget else WideCloseTarget

private fun estimatedButtonWidth(text: String): Dp = EstimatedCharWidth * text.length + ButtonChrome
