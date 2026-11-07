package org.example.project.admin.features.documents.presentation.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind

/**
 * Confirmation dialog for the manager-gated document delete action ([DocumentHeaderBar]'s "Delete"
 * button).
 */
@Composable
fun DeleteDocumentDialog(
    filename: String,
    isDeleting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    PosDialog(
        onDismiss = onDismiss,
        title = str(AdminStringKeys.DocumentDeleteConfirmTitle),
        dismissOnClickOutside = !isDeleting,
        kind = PosDialogKind.Confirm,
        primary =
            PosDialogAction(
                text = str(CommonKeys.delete),
                onClick = onConfirm,
                enabled = !isDeleting,
                isLoading = isDeleting,
            ),
        destructive = true,
    ) {
        Text(str(AdminStringKeys.DocumentDeleteConfirmBody, filename))
    }
}
