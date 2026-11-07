package org.example.project.ui.components.form

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDangerButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosSecondaryButton

/**
 * "Änderungen verwerfen?" — shown before any close, back navigation or selection change that would
 * lose a dirty [PosFormState]. Never drop a draft silently, never save it silently.
 */
@Composable
fun PosDiscardChangesDialog(
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
) {
    PosDialog(
        onDismiss = onKeepEditing,
        title = str(CommonKeys.discardChanges),
        footer = {
            PosSecondaryButton(text = str(CommonKeys.keepEditing), onClick = onKeepEditing, height = 40.dp)
            PosDangerButton(text = str(CommonKeys.discard), onClick = onDiscard, height = 40.dp)
        },
    ) {
        Text(
            text = str(CommonKeys.discardChangesMessage),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
