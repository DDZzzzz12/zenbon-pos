package org.example.project.admin.features.terminal.presentation.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind

/**
 * Confirms a deactivation, and says how it differs from revoking: the device stays paired and
 * nothing is deleted — it simply cannot sell until it is reactivated.
 */
@Composable
fun DeactivateTerminalDialog(
    terminal: TerminalDto.TerminalSummaryDto,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    PosDialog(
        onDismiss = onDismiss,
        title = str(AdminStringKeys.TerminalDeactivateTitle),
        kind = PosDialogKind.Confirm,
        primary = PosDialogAction(text = str(AdminStringKeys.TerminalDeactivate), onClick = onConfirm),
        destructive = true,
    ) {
        Text(str(AdminStringKeys.TerminalDeactivateBody, terminal.terminalId))
    }
}
