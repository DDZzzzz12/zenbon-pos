package org.example.project.core.utils

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val MinTouchTarget = 48.dp

/**
 * [base] on an expanded (desktop-sized) window, at least 48dp everywhere else.
 *
 * Not expanded means width below 840dp **or** height below 480dp (see
 * [isAtLeastExpanded]), so a phone in landscape and a tablet in portrait are both treated as touch
 * devices and get the 48dp minimum.
 */
fun touchTargetFor(
    base: Dp,
    expandedWindow: Boolean,
): Dp = if (expandedWindow) base else maxOf(base, MinTouchTarget)

/**
 * [touchTargetFor] for the current window size class.
 *
 * Reads the window class directly instead of [LocalWindowSizeClass]: that local throws when nothing
 * provides it, and shared components (toast, dropdown rows) are hosted above some app roots.
 */
@Composable
fun touchTargetSize(base: Dp): Dp = touchTargetFor(base, currentWindowAdaptiveInfo().windowSizeClass.isAtLeastExpanded())
