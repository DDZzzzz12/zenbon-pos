/**
 * Temporary Manager Session Authentication Dialog.
 *
 * @since 2.0.0
 */
package org.example.project.features.auth.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.TemporarySessionKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.PosTextField
import org.example.project.ui.theme.PosSpacing

@Composable
fun TemporarySessionDialog(
    isVisible: Boolean,
    isLoading: Boolean,
    errorMessage: String?,
    language: Language,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    if (!isVisible) return

    var identifier by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var isPinVisible by remember { mutableStateOf(false) }

    val identifierFocusRequester = remember { FocusRequester() }
    val pinFocusRequester = remember { FocusRequester() }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            identifier = ""
            pin = ""
            isPinVisible = false
            identifierFocusRequester.requestFocus()
        }
    }

    val isValid = identifier.isNotBlank() && pin.length == 4

    PosDialog(
        onDismiss = { if (!isLoading) onDismiss() },
        title = str(TemporarySessionKeys.dialogTitle),
        titleIcon = Icons.Default.Lock,
        dismissOnClickOutside = !isLoading,
        footer = {
            PosTextButton(
                text = getString(CommonKeys.cancel, language),
                onClick = onDismiss,
                enabled = !isLoading,
            )
            Spacer(modifier = Modifier.width(PosSpacing.md))
            PosButton(
                text =
                    if (isLoading) {
                        str(TemporarySessionKeys.confirmButtonLoading)
                    } else {
                        str(TemporarySessionKeys.confirmButton)
                    },
                onClick = { onConfirm(identifier, pin) },
                enabled = isValid,
                isLoading = isLoading,
            )
        },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(PosSpacing.xl),
        ) {
            Text(
                text = str(TemporarySessionKeys.instructions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PosTextField(
                value = identifier,
                onValueChange = { identifier = it },
                label = str(TemporarySessionKeys.identifierLabel),
                leadingIcon = Icons.Default.Person,
                enabled = !isLoading,
                focusRequester = identifierFocusRequester,
            )

            PosTextField(
                value = pin,
                onValueChange = { if (it.length <= 4 && it.all { char -> char.isDigit() }) pin = it },
                label = str(TemporarySessionKeys.pinLabel),
                leadingIcon = Icons.Default.Lock,
                trailingIcon = {
                    PosIconButton(
                        icon = if (isPinVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription =
                            if (isPinVisible) {
                                str(TemporarySessionKeys.hidePin)
                            } else {
                                str(TemporarySessionKeys.showPin)
                            },
                        onClick = { isPinVisible = !isPinVisible },
                    )
                },
                visualTransformation = if (isPinVisible) VisualTransformation.None else PasswordVisualTransformation(),
                enabled = !isLoading,
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
                focusRequester = pinFocusRequester,
            )

            if (errorMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(PosSpacing.lg),
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(PosSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(PosSpacing.sm),
                ) {
                    Text(
                        text = str(TemporarySessionKeys.sessionDetailsTitle),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = str(TemporarySessionKeys.sessionDuration),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = str(TemporarySessionKeys.autoExtend),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = str(TemporarySessionKeys.deviceSpecific),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}
