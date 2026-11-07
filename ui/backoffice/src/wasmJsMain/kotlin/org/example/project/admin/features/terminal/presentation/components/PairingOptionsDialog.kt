package org.example.project.admin.features.terminal.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.terminal.presentation.PairingOptionsDraft
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind

/**
 * Asked before a pairing code is generated: whether the device's pairing should also register the
 * terminal at the TSE. The box is enabled only when that is possible, and says why when it is not.
 */
@Composable
fun PairingOptionsDialog(
    draft: PairingOptionsDraft,
    onRegisterTseClient: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val availability = draft.availability
    PosDialog(
        onDismiss = onDismiss,
        title = str(AdminStringKeys.TerminalPairingOptionsTitle),
        kind = PosDialogKind.Form,
        primary =
            PosDialogAction(
                text = str(AdminStringKeys.TerminalPairingIssue),
                onClick = onConfirm,
                enabled = availability != null,
            ),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(str(AdminStringKeys.TerminalPairingOptionsMessage))
            PairingTseRegistrationChoice(draft = draft, onRegisterTseClient = onRegisterTseClient)
        }
    }
}

/** The register-at-the-TSE box and its hint; shared by this dialog and the first-terminal flow. */
@Composable
fun PairingTseRegistrationChoice(
    draft: PairingOptionsDraft,
    onRegisterTseClient: (Boolean) -> Unit,
) {
    val availability = draft.availability
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = draft.requestsTseRegistration,
                onCheckedChange = onRegisterTseClient,
                enabled = availability?.selectable == true,
            )
            Text(str(AdminStringKeys.TerminalPairingRegisterTse), style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            text = str(availability?.hintKey ?: AdminStringKeys.TerminalPairingTseLoading),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
