package org.example.project.features.employees.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.keys.EmployeeManagementKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosTextField

/**
 * PIN input field component for employee authentication.
 *
 * Displays a text field for entering a 4-digit PIN with validation,
 * optional visibility toggle, and error state handling.
 *
 * @param pin Current PIN value
 * @param onPinChange Callback when PIN is changed
 * @param language Current display language for string localization
 * @param modifier Modifier for layout customization
 * @param enabled Whether the field is enabled for input
 * @param isOptional Whether the PIN is optional (affects label)
 * @param error Optional error message to display
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
@Composable
fun PinInputField(
    pin: String,
    onPinChange: (String) -> Unit,
    language: Language,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isOptional: Boolean = true,
    error: String? = null,
) {
    var pinVisible by remember { mutableStateOf(false) }

    // Validate PIN format (4 digits)
    val isValidFormat = pin.isEmpty() || (pin.all { it.isDigit() } && pin.length <= 4)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PosTextField(
            value = pin,
            onValueChange = { newValue ->
                // Only allow digits and max 4 characters
                if (newValue.all { it.isDigit() } && newValue.length <= 4) {
                    onPinChange(newValue)
                }
            },
            enabled = enabled,
            label =
                if (isOptional) {
                    str(EmployeeManagementKeys.pinOptional)
                } else {
                    str(EmployeeManagementKeys.pinLabel)
                },
            placeholder = str(EmployeeManagementKeys.pinHint),
            visualTransformation =
                if (pinVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
            trailingIcon = {
                PosIconButton(
                    icon = if (pinVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = if (pinVisible) "Hide PIN" else "Show PIN",
                    onClick = { pinVisible = !pinVisible },
                )
            },
            isError = error != null || (!isValidFormat && pin.isNotEmpty()),
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done,
            modifier = Modifier.fillMaxWidth(),
        )

        // Error message
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp),
            )
        } else if (!isValidFormat && pin.isNotEmpty()) {
            Text(
                text = str(EmployeeManagementKeys.pinHint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp),
            )
        }

        // Helper text
        if (error == null && isValidFormat) {
            Text(
                text =
                    if (pin.isEmpty()) {
                        str(EmployeeManagementKeys.noPinSet)
                    } else if (pin.length == 4) {
                        str(EmployeeManagementKeys.pinSet)
                    } else {
                        "${pin.length}/4 ${str(EmployeeManagementKeys.pinHint).lowercase()}"
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}
