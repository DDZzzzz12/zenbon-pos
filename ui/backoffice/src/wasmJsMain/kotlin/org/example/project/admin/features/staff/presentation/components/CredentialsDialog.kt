package org.example.project.admin.features.staff.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.staff.presentation.CredentialsEditor
import org.example.project.admin.features.staff.presentation.CredentialsError
import org.example.project.admin.features.staff.presentation.CredentialsField
import org.example.project.admin.features.staff.presentation.CredentialsMode
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.form.rememberPosFormFocus
import org.example.project.ui.components.form.requiredLabel
import org.example.project.ui.guard.rememberGuardedDismiss

/**
 * Reset dialog for an employee's password or PIN — gated by re-auth of the acting manager.
 *
 * Save is never disabled by the input: pressing it validates, marks each refused field and focuses
 * the first one. Only a server failure, which belongs to no field, is a line under the form.
 */
@Composable
fun CredentialsDialog(
    editor: CredentialsEditor,
    /** Display name of the employee whose credential is reset; named in the title. */
    employeeName: String,
    onField: ((CredentialsEditor) -> CredentialsEditor) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isPin = editor.mode == CredentialsMode.PIN
    val close = rememberGuardedDismiss(dirty = editor.isDirty, onClose = onDismiss)
    val focus = rememberPosFormFocus<CredentialsField>()
    val formErrors = editor.fieldErrors.entries.associate { (field, key) -> field to str(key) }

    fun anchor(field: CredentialsField): Modifier = with(focus) { Modifier.fillMaxWidth().formAnchor(field) }
    LaunchedEffect(editor.validationAttempt) {
        if (editor.validationAttempt > 0) focus.revealFirst(CredentialsField.entries, formErrors)
    }

    PosDialog(
        onDismiss = close,
        title = str(if (isPin) AdminStringKeys.StaffResetPinNamedTitle else AdminStringKeys.StaffResetPasswordNamedTitle, employeeName),
        kind = PosDialogKind.Form,
        primary = PosDialogAction(str(AdminStringKeys.CommonSave), onSave, isLoading = editor.saving),
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PosTextField(
                value = editor.newValue,
                onValueChange = { v ->
                    onField { it.copy(newValue = if (isPin) v.filter(Char::isDigit).take(6) else v, error = null) }
                },
                label = requiredLabel(str(if (isPin) AdminStringKeys.StaffNewPin else AdminStringKeys.StaffNewPassword)),
                modifier = anchor(CredentialsField.NEW_VALUE),
                keyboardType = if (isPin) KeyboardType.NumberPassword else KeyboardType.Password,
                visualTransformation = PasswordVisualTransformation(),
                isError = CredentialsField.NEW_VALUE in formErrors,
                supportingText = formErrors[CredentialsField.NEW_VALUE]?.let { msg -> { Text(msg) } },
            )

            PosTextField(
                value = editor.confirmValue,
                onValueChange = { v ->
                    onField { it.copy(confirmValue = if (isPin) v.filter(Char::isDigit).take(6) else v, error = null) }
                },
                label = requiredLabel(str(if (isPin) AdminStringKeys.StaffRepeatPin else AdminStringKeys.StaffRepeatPassword)),
                modifier = anchor(CredentialsField.CONFIRM_VALUE),
                keyboardType = if (isPin) KeyboardType.NumberPassword else KeyboardType.Password,
                visualTransformation = PasswordVisualTransformation(),
                isError = CredentialsField.CONFIRM_VALUE in formErrors,
                supportingText = formErrors[CredentialsField.CONFIRM_VALUE]?.let { msg -> { Text(msg) } },
            )

            PosTextField(
                value = editor.managerPassword,
                onValueChange = { v -> onField { it.copy(managerPassword = v, error = null) } },
                label = requiredLabel(str(AdminStringKeys.StaffManagerPassword)),
                modifier = anchor(CredentialsField.MANAGER_PASSWORD),
                keyboardType = KeyboardType.Password,
                visualTransformation = PasswordVisualTransformation(),
                isError = CredentialsField.MANAGER_PASSWORD in formErrors,
                supportingText = formErrors[CredentialsField.MANAGER_PASSWORD]?.let { msg -> { Text(msg) } },
            )

            if (editor.error == CredentialsError.SERVER) {
                Text(
                    str(AdminStringKeys.StaffCredErrorServer),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
