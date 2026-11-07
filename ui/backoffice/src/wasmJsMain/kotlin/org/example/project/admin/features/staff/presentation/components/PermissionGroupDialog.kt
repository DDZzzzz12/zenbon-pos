package org.example.project.admin.features.staff.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.staff.presentation.GroupPicker
import org.example.project.core.domain.dto.employee.PermissionGroupDto
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.guard.rememberGuardedDismiss
import org.example.project.ui.theme.PosSpacing

/**
 * Points an employee at a permission group.
 *
 * A single choice, not a checklist: an employee belongs to exactly one group, which is what keeps
 * "why may this person do that?" answerable with one name — the thing a Kassennachschau asks for.
 */
@Composable
fun PermissionGroupDialog(
    picker: GroupPicker,
    groups: List<PermissionGroupDto.PermissionGroupResponseDto>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val close = rememberGuardedDismiss(dirty = picker.isDirty, onClose = onDismiss)
    PosDialog(
        onDismiss = close,
        title = str(AdminStringKeys.StaffChangePermissionGroup),
        maxWidth = 520.dp,
        kind = PosDialogKind.Form,
        primary =
            PosDialogAction(
                text = str(CommonKeys.save),
                onClick = onConfirm,
                enabled = picker.canSave,
                isLoading = picker.saving,
            ),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            groups.forEach { group ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !picker.saving) { onPick(group.code) }
                        .padding(vertical = PosSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = group.code == picker.selectedCode,
                        onClick = { onPick(group.code) },
                        enabled = !picker.saving,
                    )
                    Column(Modifier.padding(start = 4.dp)) {
                        // A seeded group's name is stored in English and stays editable through the
                        // API, so it is translated by code; a group an operator created keeps the
                        // name it was given. A seeded group's description is reset to its compiled
                        // English on every boot, so it is translated too (admin20 BO-21); an
                        // operator-made group's description stays as written.
                        val groupLabel = AdminStringKeys.permissionGroupName(group.code)?.let { str(it) } ?: group.name
                        val seededDescription =
                            if (group.isSystem) AdminStringKeys.permissionGroupDescription(group.code) else null
                        Text(groupLabel, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = seededDescription?.let { str(it) } ?: group.description ?: group.code,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            picker.error?.let { error ->
                Text(
                    text = str(error.messageKey, *error.messageArgs.toTypedArray()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
