package org.example.project.ui.screens.employees.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.presentation.strings.keys.EmployeeHubKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.employees.presentation.state.CredentialChangeMode
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.PosTextField

@Composable
fun CredentialChangeDialog(
    mode: CredentialChangeMode,
    employeeUsername: String,
    onChangePassword: (EmployeeDto.PasswordChangeRequestDto) -> Unit,
    onChangePin: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newValue by remember { mutableStateOf("") }
    var confirmValue by remember { mutableStateOf("") }

    val title =
        when (mode) {
            CredentialChangeMode.PASSWORD -> str(EmployeeHubKeys.resetPassword)
            CredentialChangeMode.PIN -> str(EmployeeHubKeys.resetPin)
        }
    val fieldLabel =
        when (mode) {
            CredentialChangeMode.PASSWORD -> str(EmployeeHubKeys.newPassword)
            CredentialChangeMode.PIN -> str(EmployeeHubKeys.newPin)
        }
    val confirmLabel =
        when (mode) {
            CredentialChangeMode.PASSWORD -> str(EmployeeHubKeys.confirmPassword)
            CredentialChangeMode.PIN -> str(EmployeeHubKeys.confirmPin)
        }

    val isValid =
        when (mode) {
            CredentialChangeMode.PASSWORD -> newValue.length >= 6 && newValue == confirmValue
            CredentialChangeMode.PIN -> newValue.length == 4 && newValue.all { it.isDigit() } && newValue == confirmValue
        }

    PosDialog(
        onDismiss = onDismiss,
        title = title,
        footer = {
            PosTextButton(
                text = str(EmployeeHubKeys.cancel),
                onClick = onDismiss,
            )
            PosTextButton(
                text = str(EmployeeHubKeys.save),
                enabled = isValid,
                onClick = {
                    when (mode) {
                        CredentialChangeMode.PASSWORD ->
                            onChangePassword(
                                EmployeeDto.PasswordChangeRequestDto(
                                    currentPassword = "",
                                    newPassword = newValue,
                                ),
                            )
                        CredentialChangeMode.PIN -> onChangePin(newValue)
                    }
                },
            )
        },
    ) {
        Text(
            text = employeeUsername,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PosTextField(
            value = newValue,
            onValueChange = { newValue = it },
            label = fieldLabel,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardType =
                if (mode == CredentialChangeMode.PIN) KeyboardType.NumberPassword else KeyboardType.Password,
            singleLine = true,
        )
        PosTextField(
            value = confirmValue,
            onValueChange = { confirmValue = it },
            label = confirmLabel,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardType =
                if (mode == CredentialChangeMode.PIN) KeyboardType.NumberPassword else KeyboardType.Password,
            singleLine = true,
            isError = confirmValue.isNotEmpty() && newValue != confirmValue,
        )

        if (mode == CredentialChangeMode.PIN && newValue.isNotEmpty() && (newValue.length != 4 || !newValue.all { it.isDigit() })) {
            Text(
                text = str(EmployeeHubKeys.pinAccessCode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
