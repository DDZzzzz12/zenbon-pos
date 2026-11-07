package org.example.project.admin.features.terminal.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.browser.window
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.terminal.presentation.browserPairingLink
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.formatLocalDateTimeShort
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind

@Composable
fun PairingCodeQrDialog(
    code: TerminalDto.PairingCodeResponseDto,
    onDismiss: () -> Unit,
) {
    // The server sends a naked LocalDateTime (shop-local, no zone) — render it, not the ISO string.
    val expires =
        remember(code.expiresAt) {
            runCatching { code.expiresAt.formatLocalDateTimeShort() }.getOrDefault(code.expiresAt)
        }

    // The link follows the address field, so correcting localhost for the QR corrects it here too.
    var serverUrl by remember { mutableStateOf(window.location.origin) }

    PosDialog(
        onDismiss = onDismiss,
        title = str(AdminStringKeys.TerminalPairingTitle),
        kind = PosDialogKind.Confirm,
        primary = PosDialogAction(text = str(CommonKeys.close), onClick = onDismiss),
        showSecondary = false,
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(str(AdminStringKeys.TerminalPairingInstructions), textAlign = TextAlign.Center)
            // Prefilled with the admin's own origin; the operator corrects it when that is not the
            // address devices on the network reach the server by (e.g. localhost).
            PairingQrAddressSection(
                code = code.code,
                initialServerUrl = window.location.origin,
                qrContentDescription = null,
                rootFingerprint = code.rootFingerprint,
                onServerUrlChange = { serverUrl = it },
            )
            Text("${str(AdminStringKeys.TerminalPairingCodeLabel)}: ${code.code}", style = MaterialTheme.typography.headlineSmall)
            // Selectable text rather than a copy button: the clipboard API only exists in a secure
            // context, and a back office reached over a LAN IP on http is not one.
            Text(str(AdminStringKeys.TerminalPairingLinkLabel), style = MaterialTheme.typography.labelMedium)
            SelectionContainer {
                Text(browserPairingLink(serverUrl, code.code), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
            Text(str(AdminStringKeys.TerminalPairingExpires, expires))
        }
    }
}
