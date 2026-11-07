package org.example.project.admin.features.staff.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.staff.presentation.EmployeeEditor
import org.example.project.admin.features.staff.presentation.EmployeeField
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.domain.enums.employee.isTerminal
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.enumStrings.getName
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosPickerField
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.form.requiredLabel
import org.example.project.ui.guard.rememberGuardedDismiss

/** One dialog per endpoint: this edits everything `updateEmployee`/`createEmployee` accepts —
 *  basics and role — as a single atomic save.
 *
 *  The exception-permission selector that used to sit here is gone: Stage 2a retired per-employee
 *  grants server-side, so it edited a field nothing read. What an employee may reach is their
 *  permission group, assigned from the profile section through its own PERMISSION_ADMIN endpoint. */
@Composable
fun EmployeeEditorDialog(
    editor: EmployeeEditor,
    roleChangeBlocked: Boolean,
    /** EMPLOYEE_WRITE: personnel number, notes and training mode are read-only without it. */
    canEditAdministrativeFields: Boolean,
    /** Roles the actor may grant (`canGrantRole`); the picker hides the rest. */
    canGrant: (EmployeeRole) -> Boolean,
    onField: ((EmployeeEditor) -> EmployeeEditor) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    /** The form differs from how it was opened; closing then asks before discarding. */
    dirty: Boolean = false,
) {
    val focus = rememberPosFormFocus<EmployeeField>()
    val formErrors = editor.fieldErrors.entries.associate { (field, key) -> field to str(key) }

    fun anchor(field: EmployeeField): Modifier = with(focus) { Modifier.formAnchor(field) }
    LaunchedEffect(editor.validationAttempt) {
        if (editor.validationAttempt > 0) focus.revealFirst(EmployeeField.entries, formErrors)
    }

    val close = rememberGuardedDismiss(dirty = dirty, onClose = onCancel)
    PosDialog(
        onDismiss = close,
        title = str(if (editor.isCreate) AdminStringKeys.StaffCreateTitle else AdminStringKeys.StaffEditTitle),
        kind = PosDialogKind.Form,
        primary = PosDialogAction(str(CommonKeys.save), onSave, isLoading = editor.saving),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // A missing required field is marked ON the field (UI-P2-03) and focused on Save. Only
            // errors that belong to no field (role guard, a refused save) stay a line above the form.
            editor.fieldError?.let { err ->
                Text(
                    text =
                        when (err) {
                            "roleGuard" -> str(AdminStringKeys.StaffRoleGuardBlocked)
                            "save" -> str(AdminStringKeys.ProductErrorSaveFailed)
                            else -> err
                        },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (editor.isCreate) {
                PosTextField(
                    editor.username,
                    { v -> onField { it.copy(username = v) } },
                    requiredLabel(str(AdminStringKeys.StaffFieldUsername)),
                    modifier = anchor(EmployeeField.USERNAME),
                    isError = EmployeeField.USERNAME in formErrors,
                    supportingText = formErrors[EmployeeField.USERNAME]?.let { msg -> { Text(msg) } },
                )
                PosTextField(
                    editor.password,
                    { v -> onField { it.copy(password = v) } },
                    requiredLabel(str(AdminStringKeys.StaffFieldPassword)),
                    modifier = anchor(EmployeeField.PASSWORD),
                    isError = EmployeeField.PASSWORD in formErrors,
                    supportingText = formErrors[EmployeeField.PASSWORD]?.let { msg -> { Text(msg) } },
                )
            } else {
                // username is immutable
                PosTextField(editor.username, {}, str(AdminStringKeys.StaffFieldUsername), enabled = false)
            }

            // The guard disables the PICKER, not the save button: a manager editing only the
            // notes on the last active admin must still be able to save.
            RolePicker(
                selected = editor.role,
                enabled = !roleChangeBlocked,
                canGrant = canGrant,
            ) { r -> onField { it.copy(role = r) } }
            if (roleChangeBlocked) {
                Text(
                    str(AdminStringKeys.StaffRoleGuardBlocked),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // A person still holding the terminal role (upgraded installs) is treated as a station on
            // every table open. The picker no longer offers the role; say why theirs should change.
            if (!editor.isCreate && EmployeeRole.getRole(editor.role).isTerminal()) {
                Text(
                    str(AdminStringKeys.StaffRoleTerminalLegacy),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Administrative fields: EMPLOYEE_WRITE, also on one's own record — the server refuses a
            // change without it (SET-01, rule 8).
            PosTextField(
                editor.personnelNumber,
                { v -> onField { it.copy(personnelNumber = v) } },
                str(AdminStringKeys.StaffFieldNumber),
                enabled = canEditAdministrativeFields,
            )
            PosTextField(
                editor.notes,
                { v -> onField { it.copy(notes = v) } },
                str(AdminStringKeys.StaffFieldNotes),
                singleLine = false,
                minLines = 2,
                enabled = canEditAdministrativeFields,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(str(AdminStringKeys.StaffFieldTraining), Modifier.weight(1f))
                Switch(
                    checked = editor.trainingMode,
                    onCheckedChange = { v -> onField { it.copy(trainingMode = v) } },
                    enabled = canEditAdministrativeFields,
                )
            }
        }
    }
}

@Composable
private fun RolePicker(
    selected: String,
    enabled: Boolean,
    canGrant: (EmployeeRole) -> Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        PosPickerField(
            // Localized (admin20 BO-21): `displayName` is the enum's English label.
            value = EmployeeRole.getRole(selected).getName(LocalAppLanguage.current),
            onClick = { if (enabled) expanded = true },
            label = str(AdminStringKeys.StaffFieldRole),
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
        )
        PosDropdown(expanded, { expanded = false }) {
            // A role above the actor is hidden, not disabled: the server refuses it (403), and a
            // manager has no business being shown the administrator role at all.
            EmployeeRole.entries.filter { it.isTerminal() || canGrant(it) }.forEach { r ->
                // TERMINAL_DEVICE belongs to the shared order-station account; the server refuses it
                // for anyone created or edited here. Listed disabled rather than hidden so the picker
                // explains where it went.
                val assignable = !r.isTerminal()
                PosDropdownItem(
                    text = r.getName(LocalAppLanguage.current),
                    selected = r.name == selected,
                    enabled = assignable,
                    supportingText = if (assignable) null else str(AdminStringKeys.StaffRoleTerminalReserved),
                    onClick = {
                        onSelect(r.name)
                        expanded = false
                    },
                )
            }
        }
    }
}
