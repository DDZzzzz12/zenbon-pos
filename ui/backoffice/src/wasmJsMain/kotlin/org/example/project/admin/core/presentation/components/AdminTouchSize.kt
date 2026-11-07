package org.example.project.admin.core.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import org.example.project.core.utils.touchTargetSize

/**
 * [base] on an expanded (desktop-sized) window, at least 48dp on any other window (phone, phone
 * landscape, tablet portrait). Window-based on purpose: it is used by dialogs, drawers and the
 * shell, whose size follows the window, not by panes (which measure their own content width).
 */
@Composable
fun adminTouchSize(base: Dp): Dp = touchTargetSize(base)
