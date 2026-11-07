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
 * Confirms removing a retired terminal's client from the TSE, and says that it is permanent for that
 * client ID until the terminal is registered at the TSE again.
 */
@Composable
fun DeregisterTseClientDialog(
    terminal: TerminalDto.TerminalSummaryDto,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    PosDialog(
        onDismiss = onDismiss,
        title = str(AdminStringKeys.TerminalTseDeregisterTitle),
        kind = PosDialogKind.Confirm,
        primary = PosDialogAction(text = str(AdminStringKeys.TerminalTseDeregister), onClick = onConfirm),
        destructive = true,
    ) {
        Text(str(AdminStringKeys.TerminalTseDeregisterMessage, terminal.terminalId))
    }
}
