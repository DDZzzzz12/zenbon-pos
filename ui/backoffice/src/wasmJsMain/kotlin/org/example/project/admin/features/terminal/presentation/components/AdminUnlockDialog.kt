package org.example.project.admin.features.terminal.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.terminal.presentation.UnlockDraft
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosTextField

@Composable
fun AdminUnlockDialog(
    draft: UnlockDraft,
    onPin: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    PosDialog(
        onDismiss = onDismiss,
        title = str(AdminStringKeys.TerminalTseUnlockTitle),
        kind = PosDialogKind.Form,
        primary =
            PosDialogAction(
                text = str(AdminStringKeys.TerminalTseUnlockSubmit),
                onClick = onSubmit,
                enabled = !draft.unlocking && draft.pin.isNotBlank(),
                isLoading = draft.unlocking,
            ),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(str(AdminStringKeys.TerminalTseUnlockMessage))
            PosTextField(
                value = draft.pin,
                onValueChange = onPin,
                label = str(AdminStringKeys.TerminalTseUnlockPinLabel),
                keyboardType = KeyboardType.NumberPassword,
                visualTransformation = PasswordVisualTransformation(),
            )
            draft.error?.let { err ->
                Text(
                    text = str(err.messageKey, *err.messageArgs.toTypedArray()),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
