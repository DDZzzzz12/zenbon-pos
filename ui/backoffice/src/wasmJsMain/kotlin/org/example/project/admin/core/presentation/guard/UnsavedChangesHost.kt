package org.example.project.admin.core.presentation.guard

import androidx.compose.runtime.Composable
import org.example.project.admin.core.util.dom.BlockDomOverlayWhileVisible
import org.example.project.core.presentation.components.DiscardChangesDialog
import org.example.project.ui.guard.LeaveGuard

/**
 * Shows the "Discard changes?" prompt whenever [guard] holds a pending leave.
 *
 * Hosted once at the app root, outside the compact/wide layout branch, so a window resize while the
 * prompt is open cannot strand it. The document scanner's camera `<video>` is a DOM element and always
 * draws above the canvas (and the scanner registers its captured pages as unsaved), so the prompt hides
 * it while visible — otherwise the prompt would be invisible there.
 */
@Composable
fun UnsavedChangesHost(guard: LeaveGuard) {
    if (guard.pending == null) return
    BlockDomOverlayWhileVisible()
    DiscardChangesDialog(
        onDiscard = guard::discard,
        onKeepEditing = guard::keepEditing,
    )
}
