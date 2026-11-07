/**
 * Reusable dialog component for adding or editing items (payment types, VAT rates, tables).
 *
 * Built on [PosDialog] with the standard footer: the dismiss action at the start, the confirm action at
 * the end. Form content goes in a spaced column.
 *
 * @param title Dialog title
 * @param onDismiss Callback when dialog is dismissed
 * @param dirty Whether the form holds unsaved edits; when true, dismissing (scrim, Back, close, Cancel) asks
 *   first through the app's leave guard. The default keeps today's behaviour (and the till has no guard).
 * @param onConfirm Callback when confirm button is clicked with form data
 * @param confirmLoading the save is in flight: the confirm action shows progress and cannot be pressed again
 * @param content Dialog content with form fields
 *
 * @since 1.0.0
 */
package org.example.project.ui.screens.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.guard.rememberGuardedDismiss

@Composable
fun AddItemDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    confirmText: String = "Save",
    dismissText: String = "Cancel",
    dirty: Boolean = false,
    confirmLoading: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val close = rememberGuardedDismiss(dirty = dirty, onClose = onDismiss)
    PosDialog(
        onDismiss = close,
        title = title,
        modifier = modifier,
        kind = PosDialogKind.Form,
        primary = PosDialogAction(confirmText, onConfirm, confirmEnabled, isLoading = confirmLoading),
        secondary = PosDialogAction(dismissText, close),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
    }
}
