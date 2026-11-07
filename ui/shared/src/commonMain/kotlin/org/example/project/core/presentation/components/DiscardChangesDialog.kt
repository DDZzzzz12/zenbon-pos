package org.example.project.core.presentation.components

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosTextButton

/**
 * Reusable confirmation dialog shown when the user tries to close a form that
 * has unsaved changes. Caller decides when to show (tracks dirty state) and
 * wires the confirm/dismiss actions.
 */
@Composable
fun DiscardChangesDialog(
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
) {
    PosDialog(
        onDismiss = onKeepEditing,
        title = str(CommonKeys.discardChanges),
        footer = {
            PosTextButton(
                text = str(CommonKeys.keepEditing),
                onClick = onKeepEditing,
            )
            PosTextButton(
                text = str(CommonKeys.discard),
                onClick = onDiscard,
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
            )
        },
    ) {
        Text(str(CommonKeys.discardChangesMessage))
    }
}
