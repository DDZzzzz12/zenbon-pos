/**
 * Confirmation dialog for abandoning a queued TSE signature.
 *
 * @since 2.0.0
 */
package org.example.project.features.settings.presentation.components.tse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.TseSettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDangerButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.PosTextField

/**
 * Asks for the mandatory reason before a queued TSE signature is abandoned.
 *
 * Cancelling a queue entry is not "dismissing a notification" — it permanently gives up on signing
 * a Vorgang that §146a AO requires to be signed. The entry is marked `CANCELLED`, the monthly close
 * reports it as an unsigned Beleg, and the reason entered here is written to the
 * `TransactionAuditLog` as the record of who abandoned it and why.
 *
 * The server rejects a blank reason, so this dialog enforces the same rule locally rather than
 * letting the operator discover it as a failed request.
 *
 * @param entryId Queue entry being abandoned, shown so the operator can confirm the target.
 * @param isPerforming Whether a queue operation is already running.
 * @param onDismiss Called when the operator backs out.
 * @param onConfirm Called with the entered reason.
 */
@Composable
fun CancelQueueEntryDialog(
    entryId: Long,
    isPerforming: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (reason: String) -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

    PosDialog(
        onDismiss = onDismiss,
        title = str(TseSettingsKeys.cancelEntryTitle),
        titleIcon = Icons.Default.Warning,
        footer = {
            PosTextButton(text = str(CommonKeys.cancel), onClick = onDismiss)
            PosDangerButton(
                text = str(TseSettingsKeys.cancelEntryConfirm),
                enabled = !isPerforming,
                onClick = {
                    if (reason.isBlank()) {
                        showError = true
                    } else {
                        onConfirm(reason.trim())
                    }
                },
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "#$entryId",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = str(TseSettingsKeys.cancelEntryWarning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            PosTextField(
                value = reason,
                onValueChange = {
                    reason = it
                    showError = false
                },
                label = str(TseSettingsKeys.cancelEntryReasonLabel),
                isError = showError,
                errorMessage = if (showError) str(TseSettingsKeys.cancelEntryReasonRequired) else null,
            )
        }
    }
}
