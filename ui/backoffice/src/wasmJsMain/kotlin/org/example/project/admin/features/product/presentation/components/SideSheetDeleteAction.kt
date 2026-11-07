package org.example.project.admin.features.product.presentation.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDangerIconButton
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.theme.LocalPosLayout

/**
 * The Löschen control at the start of an edit drawer's footer: a red text button, an icon-only
 * button on a phone where the footer has to share its width with Abbrechen and Speichern.
 * It only asks for the delete confirmation; the caller composes it for saved records and `canManageProducts`.
 */
@Composable
fun SideSheetDeleteAction(onDelete: () -> Unit) {
    if (LocalPosLayout.current.isCompact) {
        PosDangerIconButton(Icons.Default.Delete, str(AdminStringKeys.CommonDelete), onDelete, size = 48.dp)
    } else {
        PosTextButton(
            text = str(AdminStringKeys.CommonDelete),
            icon = Icons.Default.Delete,
            onClick = onDelete,
            height = 40.dp,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        )
    }
}
