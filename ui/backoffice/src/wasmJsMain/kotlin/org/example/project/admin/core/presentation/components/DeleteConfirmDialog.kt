package org.example.project.admin.core.presentation.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind

/**
 * The one delete confirmation of adminApp: names the object in the title, says what happens in the
 * body, and draws the standard [PosDialog] footer (Cancel + a destructive primary).
 *
 * @param entityType localized noun for the kind of object ("Produkt"); used by the blocked title.
 * @param entityName the object's own name, shown in the title.
 * @param consequence what the delete (or deactivate) does beyond removing the object. The "cannot be
 *   undone" line is kept after it while [irreversible] is true, and is the whole body when null.
 * @param blockedReason when set, the delete is refused: the dialog explains why and only offers Close.
 * @param titleKey title template with a `{0}` name slot; override for e.g. a deactivate confirm.
 */
@Composable
fun DeleteConfirmDialog(
    entityType: String,
    entityName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    consequence: String? = null,
    blockedReason: String? = null,
    confirmText: String = str(AdminStringKeys.CommonDelete),
    irreversible: Boolean = true,
    titleKey: StringKey = AdminStringKeys.DeleteConfirmNamedTitle,
) {
    if (blockedReason != null) {
        PosDialog(
            onDismiss = onDismiss,
            title = str(AdminStringKeys.DeleteBlockedTitle, entityType.trim()),
            kind = PosDialogKind.Confirm,
            primary = PosDialogAction(str(CommonKeys.close), onDismiss),
            showSecondary = false,
        ) { Text(blockedReason) }
        return
    }
    PosDialog(
        onDismiss = onDismiss,
        title = deleteConfirmTitle(str(titleKey), entityName, fallback = entityType),
        kind = PosDialogKind.Confirm,
        primary = PosDialogAction(confirmText, onConfirm),
        destructive = true,
    ) {
        if (consequence != null) Text(consequence)
        if (consequence == null || irreversible) Text(str(AdminStringKeys.DeleteConfirmMessage))
    }
}
