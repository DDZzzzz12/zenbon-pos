package org.example.project.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.ui.layout.PaneBreakpoints

/** The width of an ordinary [PosDialog] card; a larger `maxWidth` marks a form. */
val PosDialogDefaultMaxWidth: Dp = 420.dp

/** Whether a dialog fills the screen. Tablet and desktop (window at or above the phone breakpoint) never change. */
fun posDialogUsesFullScreen(
    windowWidth: Dp,
    mode: PosDialogPhoneMode,
    hasFooter: Boolean,
    maxWidth: Dp,
    kind: PosDialogKind? = null,
): Boolean {
    if (windowWidth >= PaneBreakpoints.PHONE_MAX) return false
    return when (mode) {
        PosDialogPhoneMode.FullScreen -> true
        PosDialogPhoneMode.Compact -> false
        PosDialogPhoneMode.Auto ->
            when (kind) {
                PosDialogKind.Form -> true
                PosDialogKind.Confirm -> false
                null -> hasFooter && maxWidth > PosDialogDefaultMaxWidth
            }
    }
}
