package org.example.project.features.dashboard.presentation.components.billing

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.example.project.core.presentation.strings.keys.BillReceiptKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDangerButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosTextButton

/**
 * Shown when a re-opening Storno was refused because new guests now occupy the table. Confirming
 * voids the bill without re-opening the table, so the new guests keep their session.
 */
@Composable
fun StornoRestoreConflictDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    PosDialog(
        onDismiss = onDismiss,
        title = str(BillReceiptKeys.restoreConflictTitle),
        footer = {
            PosTextButton(
                text = str(CommonKeys.cancel),
                onClick = onDismiss,
            )
            PosDangerButton(
                text = str(BillReceiptKeys.stornoWithoutRestoreButton),
                onClick = onConfirm,
            )
        },
    ) {
        Text(str(BillReceiptKeys.restoreConflictMessage))
    }
}
