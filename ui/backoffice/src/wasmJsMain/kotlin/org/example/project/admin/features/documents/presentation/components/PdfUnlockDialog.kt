package org.example.project.admin.features.documents.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.documents.presentation.UnlockPrompt
import org.example.project.admin.features.documents.presentation.UnlockStep
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.PosTextField

/**
 * Asks what to do with a password-protected PDF, in two steps: *unlock it* (with the password), or
 * *keep it locked* — which first shows what that costs (the document cannot be processed) and needs a
 * second confirmation.
 *
 * The password is composition-local: it is handed to [onSubmit] and never stored by the ViewModel. It
 * is kept across a refusal so a typo can be fixed without retyping, and dies with the dialog.
 * Dismissing outside the dialog does nothing (an accidental click must not abandon the upload);
 * the explicit Cancel does.
 */
@Composable
fun PdfUnlockDialog(
    prompt: UnlockPrompt,
    isSubmitting: Boolean,
    onSubmit: (password: String) -> Unit,
    onDecline: () -> Unit,
    onBack: () -> Unit,
    onConfirmStoreLocked: () -> Unit,
    onCancel: () -> Unit,
) {
    when (prompt.step) {
        UnlockStep.Ask -> AskStep(prompt, isSubmitting, onSubmit, onDecline, onCancel)
        UnlockStep.ConfirmDecline -> ConfirmDeclineStep(isSubmitting, onBack, onConfirmStoreLocked)
    }
}

@Composable
private fun AskStep(
    prompt: UnlockPrompt,
    isSubmitting: Boolean,
    onSubmit: (String) -> Unit,
    onDecline: () -> Unit,
    onCancel: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    PosDialog(
        onDismiss = onCancel,
        title = str(AdminStringKeys.DocumentUploadUnlockTitle),
        titleIcon = Icons.Default.Lock,
        // Three footer buttons, one with a long label: the default 420 dp truncates "Entsperren und speichern".
        maxWidth = 560.dp,
        dismissOnClickOutside = false,
        footer = {
            // The way out that has a cost comes first and in the warning colour, as in the design;
            // Cancel is the plain secondary beside the primary.
            PosTextButton(
                text = str(AdminStringKeys.DocumentUploadUnlockDecline),
                onClick = onDecline,
                enabled = !isSubmitting,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            )
            PosSecondaryButton(text = str(CommonKeys.cancel), onClick = onCancel, enabled = !isSubmitting)
            PosButton(
                text = str(AdminStringKeys.DocumentUploadUnlockAction),
                onClick = { onSubmit(password) },
                enabled = !isSubmitting,
            )
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = str(AdminStringKeys.DocumentUploadUnlockExplainer, prompt.fileName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PosTextField(
                value = password,
                onValueChange = { password = it },
                label = str(AdminStringKeys.DocumentUploadUnlockPasswordLabel),
                modifier = Modifier.fillMaxWidth(),
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
                visualTransformation = PasswordVisualTransformation(),
                enabled = !isSubmitting,
            )
            Text(
                text = str(AdminStringKeys.DocumentUploadUnlockPasswordHint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            prompt.rejection?.let { rejection ->
                Text(
                    text = str(rejection.messageKey, *rejection.messageArgs.toTypedArray()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ConfirmDeclineStep(
    isSubmitting: Boolean,
    onBack: () -> Unit,
    onConfirmStoreLocked: () -> Unit,
) {
    PosDialog(
        onDismiss = onBack,
        title = str(AdminStringKeys.DocumentUploadUnlockDeclineTitle),
        titleIcon = Icons.Default.Warning,
        dismissOnClickOutside = false,
        kind = PosDialogKind.Confirm,
        primary =
            PosDialogAction(
                text = str(AdminStringKeys.DocumentUploadUnlockDeclineConfirm),
                onClick = onConfirmStoreLocked,
                enabled = !isSubmitting,
            ),
        secondary =
            PosDialogAction(
                text = str(AdminStringKeys.DocumentUploadUnlockBack),
                onClick = onBack,
                enabled = !isSubmitting,
            ),
    ) {
        Text(
            text = str(AdminStringKeys.DocumentUploadUnlockDeclineWarning),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
