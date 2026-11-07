package org.example.project.admin.features.terminal.presentation.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.terminal.PairingQrPayload
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.ServerAddress
import org.example.project.ui.components.PosTextField
import qrgenerator.qrkitpainter.rememberQrKitPainter

/**
 * The pairing QR together with the server address it carries — shared by the Terminals pairing
 * dialog and the first-terminal wizard.
 *
 * The server cannot know the address devices on the shop network reach it by, so the address starts
 * as [initialServerUrl] (the browser origin) and stays editable. Every valid edit (per
 * [ServerAddress.normalize]) re-renders the QR with the normalized base URL; an invalid edit keeps the
 * last valid QR and says so; a loopback host gets a warning, because a scanning device would talk to
 * itself.
 */
@Composable
fun PairingQrAddressSection(
    code: String,
    initialServerUrl: String,
    qrContentDescription: String?,
    modifier: Modifier = Modifier,
    rootFingerprint: String? = null,
    onServerUrlChange: (String) -> Unit = {},
) {
    var input by remember(initialServerUrl) { mutableStateOf(initialServerUrl) }
    var qrServerUrl by remember(initialServerUrl) {
        mutableStateOf(ServerAddress.normalize(initialServerUrl) ?: initialServerUrl)
    }
    val inputInvalid = ServerAddress.normalize(input) == null
    val payload = remember(qrServerUrl, code, rootFingerprint) { PairingQrPayload(serverUrl = qrServerUrl, code = code, rootFp = rootFingerprint).encode() }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PosTextField(
            value = input,
            onValueChange = { typed ->
                input = typed
                ServerAddress.normalize(typed)?.let {
                    qrServerUrl = it
                    onServerUrlChange(it)
                }
            },
            label = str(AdminStringKeys.TerminalPairingServerAddressLabel),
            modifier = Modifier.fillMaxWidth(),
            errorMessage = if (inputInvalid) str(AdminStringKeys.TerminalPairingServerAddressInvalid) else null,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
        )
        if (ServerAddress.isLoopback(qrServerUrl)) {
            Text(
                str(AdminStringKeys.TerminalPairingLoopbackWarning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        if (rootFingerprint != null) {
            // The same code the QR carries, for a device without a camera. Selectable, not a copy
            // button: the clipboard API needs a secure context.
            Text(str(AdminStringKeys.TerminalPairingRootCodeLabel), style = MaterialTheme.typography.labelMedium)
            SelectionContainer {
                Text(rootFingerprint, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
            Text(
                str(AdminStringKeys.TerminalPairingRootCodeHint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        // key(payload): a fresh painter per payload guarantees the QR re-renders after an edit.
        key(payload) {
            Image(
                painter = rememberQrKitPainter(data = payload),
                contentDescription = qrContentDescription,
                modifier = Modifier.size(220.dp),
            )
        }
    }
}
