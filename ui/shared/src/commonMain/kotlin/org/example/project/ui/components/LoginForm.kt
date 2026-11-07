package org.example.project.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Pre-resolved label strings for [LoginForm]. Each host app supplies these from its
 * own localization system, so the shared form stays free of any i18n runtime.
 */
data class LoginFormLabels(
    val identifier: String,
    val secret: String,
    val submit: String,
    val showPassword: String,
    val hidePassword: String,
)

/**
 * Stateless credential form shared by composeApp (POS terminal) and adminApp (web).
 *
 * Identifier + password fields (with a visibility toggle) and a submit button, built on
 * the shared [PosTextField]/[PosButton]. On desktop the fields get the on-screen virtual
 * keyboard automatically; on web/android they don't — same component, platform-appropriate.
 *
 * State is fully hoisted; the caller owns the values and wires its own ViewModel.
 */
@Composable
fun LoginForm(
    identifier: String,
    secret: String,
    onIdentifierChange: (String) -> Unit,
    onSecretChange: (String) -> Unit,
    onSubmit: () -> Unit,
    labels: LoginFormLabels,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    canSubmit: Boolean = true,
    identifierIcon: ImageVector? = null,
    secretIcon: ImageVector? = null,
    belowFields: (@Composable () -> Unit)? = null,
) {
    var passwordVisible by remember { mutableStateOf(false) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PosTextField(
            value = identifier,
            onValueChange = onIdentifierChange,
            label = labels.identifier,
            leadingIcon = identifierIcon,
            enabled = !isLoading,
            imeAction = ImeAction.Next,
            modifier = Modifier.fillMaxWidth(),
        )

        PosTextField(
            value = secret,
            onValueChange = onSecretChange,
            label = labels.secret,
            leadingIcon = secretIcon,
            enabled = !isLoading,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                PosIconButton(
                    icon = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = if (passwordVisible) labels.hidePassword else labels.showPassword,
                    onClick = { passwordVisible = !passwordVisible },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        belowFields?.invoke()

        PosButton(
            text = labels.submit,
            onClick = onSubmit,
            enabled = canSubmit,
            isLoading = isLoading,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
