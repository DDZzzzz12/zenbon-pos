package org.example.project.admin.features.permissions.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.permissions.presentation.GroupDraft
import org.example.project.admin.features.permissions.presentation.GroupEditorField
import org.example.project.admin.features.permissions.presentation.PermissionGroupsState
import org.example.project.core.domain.dto.employee.PermissionGroupDto
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosDangerOutlinedButton
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.form.requiredLabel

/**
 * The right-hand editor for one group.
 *
 * Mirrors the server's guardrails rather than inventing its own: a seeded group's capabilities are
 * read-only (its label is not), and delete is offered only where the server would accept it. The
 * client half only hides controls — the server refuses regardless (CLAUDE.md rule 8).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupEditorPanel(
    data: PermissionGroupsState.Data,
    group: PermissionGroupDto.PermissionGroupResponseDto,
    onEditName: (String) -> Unit,
    onEditDescription: (String) -> Unit,
    onToggleCapability: (String) -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = data.draft ?: GroupDraft.of(group)
    val dirty = data.isDirty
    val busy = data.saving || data.deleting
    val focus = rememberPosFormFocus<GroupEditorField>()
    val formErrors = data.draftErrors.entries.associate { (field, key) -> field to str(key) }
    LaunchedEffect(data.validationAttempt) {
        if (data.validationAttempt > 0) focus.revealFirst(GroupEditorField.entries, formErrors)
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(group.code, style = MaterialTheme.typography.headlineSmall)
                Text(
                    text =
                        if (group.isSystem) {
                            str(AdminStringKeys.PermissionGroupSystemNote, group.memberCount.toString())
                        } else {
                            str(AdminStringKeys.PermissionGroupMembers, group.memberCount.toString())
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // A seeded group's name and description are reset to the compiled definition on every
        // server boot, so editing them could not stick, and the English text the API returns read
        // "Accountant" beside a list saying "Buchhaltung" (QA admin19 NOTE-c). Show the translated
        // text read-only instead; a custom group keeps its editable, operator-authored fields.
        val seededName = if (group.isSystem) AdminStringKeys.permissionGroupName(group.code) else null
        val seededDescription = if (group.isSystem) AdminStringKeys.permissionGroupDescription(group.code) else null
        PosTextField(
            value = seededName?.let { str(it) } ?: draft.name,
            onValueChange = onEditName,
            // Required only where it is editable: a seeded group's name is fixed and shown read-only.
            label = str(AdminStringKeys.PermissionGroupName).let { if (seededName == null) requiredLabel(it) else it },
            enabled = !busy,
            readOnly = seededName != null,
            locked = seededName != null,
            isError = GroupEditorField.NAME in formErrors,
            supportingText = nameSupport(formErrors[GroupEditorField.NAME], locked = seededName != null),
            modifier = with(focus) { Modifier.fillMaxWidth().formAnchor(GroupEditorField.NAME) },
        )
        PosTextField(
            value = seededDescription?.let { str(it) } ?: draft.description,
            onValueChange = onEditDescription,
            label = str(AdminStringKeys.PermissionGroupDescription),
            enabled = !busy,
            readOnly = seededDescription != null,
            locked = seededDescription != null,
            singleLine = seededDescription == null,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(str(AdminStringKeys.PermissionCapabilities), style = MaterialTheme.typography.titleMedium)
        if (group.isSystem) {
            // Not a warning: the fixed set is the point of a seeded group, and it is what keeps the
            // database and the compiled seed from drifting apart on the next boot.
            Text(
                str(AdminStringKeys.PermissionGroupCapabilitiesFixed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        CapabilityPicker(
            byArea = data.catalogByArea,
            selected = draft.capabilities,
            enabled = !group.isSystem && !busy,
            onToggle = onToggleCapability,
            modifier = Modifier.padding(top = 4.dp),
        )

        data.actionError?.let { error ->
            Text(
                text = str(error.messageKey, *error.messageArgs.toTypedArray()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // A seeded group has nothing editable (name, description and capabilities are fixed), so
            // it offers no Save at all rather than a permanently disabled one.
            if (!group.isSystem) {
                PosButton(
                    text = str(CommonKeys.save),
                    onClick = onSave,
                    enabled = dirty && !busy,
                    isLoading = data.saving,
                )
            }
            if (dirty) {
                PosButton(
                    text = str(CommonKeys.cancel),
                    onClick = onDiscard,
                    enabled = !busy,
                    colors = ButtonDefaults.outlinedButtonColors(),
                )
            }
            if (group.isDeletable) {
                // SYS-20: a destructive page action is outlined; the filled red button is only the
                // confirm inside the dialog it opens (PermissionGroupsScreen).
                PosDangerOutlinedButton(
                    text = str(CommonKeys.delete),
                    onClick = onDelete,
                    // A group with members cannot be deleted: every member would resolve to no
                    // capabilities and reach nothing, with nothing on screen explaining why.
                    enabled = group.memberCount == 0 && !busy,
                    isLoading = data.deleting,
                )
            }
        }
        if (group.isDeletable && group.memberCount > 0) {
            Text(
                str(AdminStringKeys.PermissionGroupHasMembers),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The name field's supporting line: a validation error first, else why a seeded name is locked. */
private fun nameSupport(
    error: String?,
    locked: Boolean,
): (@Composable () -> Unit)? =
    when {
        error != null -> {
            { Text(error) }
        }
        locked -> {
            { Text(str(AdminStringKeys.PermissionGroupLockedReason)) }
        }
        else -> null
    }
