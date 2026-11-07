package org.example.project.admin.features.permissions.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.permissions.presentation.CreateGroupDraft
import org.example.project.admin.features.permissions.presentation.CreateGroupField
import org.example.project.core.domain.dto.employee.PermissionGroupDto
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.form.requiredLabel
import org.example.project.ui.guard.rememberGuardedDismiss

/**
 * Create-group form.
 *
 * `PosDialog` scrolls its own content, so nothing here adds a `verticalScroll` — nesting one
 * crashes the measure pass on wasm.
 */
@Composable
fun CreateGroupDialog(
    draft: CreateGroupDraft,
    byArea: Map<String, List<PermissionGroupDto.CapabilityInfoDto>>,
    onEdit: ((CreateGroupDraft) -> CreateGroupDraft) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
) {
    val close = rememberGuardedDismiss(dirty = draft.hasInput, onClose = onDismiss)
    val focus = rememberPosFormFocus<CreateGroupField>()
    val formErrors = draft.fieldErrors.entries.associate { (field, key) -> field to str(key) }

    fun anchor(field: CreateGroupField): Modifier = with(focus) { Modifier.fillMaxWidth().formAnchor(field) }
    LaunchedEffect(draft.validationAttempt) {
        if (draft.validationAttempt > 0) focus.revealFirst(CreateGroupField.entries, formErrors)
    }

    PosDialog(
        onDismiss = close,
        title = str(AdminStringKeys.PermissionGroupCreate),
        maxWidth = 560.dp,
        kind = PosDialogKind.Form,
        primary =
            PosDialogAction(
                text = str(CommonKeys.save),
                onClick = onSubmit,
                isLoading = draft.saving,
            ),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val codeError = formErrors[CreateGroupField.CODE]
            PosTextField(
                value = draft.code,
                onValueChange = { value -> onEdit { it.copy(code = value) } },
                label = requiredLabel(str(AdminStringKeys.PermissionGroupCode)),
                enabled = !draft.saving,
                isError = codeError != null,
                // The code is normalised and then immutable — employees point at a group by code,
                // so a later rename would silently unassign every member. Showing the normalised
                // form now stops an operator creating "floor lead" and not finding it afterwards.
                // A refused code shows why instead.
                supportingText = {
                    Text(
                        codeError ?: str(AdminStringKeys.PermissionGroupCodeHint, draft.normalizedCode.ifEmpty { "—" }),
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                modifier = anchor(CreateGroupField.CODE),
            )
            PosTextField(
                value = draft.name,
                onValueChange = { value -> onEdit { it.copy(name = value) } },
                label = requiredLabel(str(AdminStringKeys.PermissionGroupName)),
                enabled = !draft.saving,
                isError = CreateGroupField.NAME in formErrors,
                supportingText = formErrors[CreateGroupField.NAME]?.let { msg -> { Text(msg) } },
                modifier = anchor(CreateGroupField.NAME),
            )
            PosTextField(
                value = draft.description,
                onValueChange = { value -> onEdit { it.copy(description = value) } },
                label = str(AdminStringKeys.PermissionGroupDescription),
                enabled = !draft.saving,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(str(AdminStringKeys.PermissionCapabilities), style = MaterialTheme.typography.titleMedium)
            CapabilityPicker(
                byArea = byArea,
                selected = draft.capabilities,
                enabled = !draft.saving,
                onToggle = { name ->
                    onEdit { d ->
                        d.copy(
                            capabilities =
                                if (name in d.capabilities) d.capabilities - name else d.capabilities + name,
                        )
                    }
                },
            )

            draft.error?.let { error ->
                Text(
                    text = str(error.messageKey, *error.messageArgs.toTypedArray()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
