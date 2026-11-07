/**
 * TSE Firmware Apply confirmation dialog.
 *
 * Warning dialog explaining risks of firmware update
 * (power loss = bricked device, long-running operation).
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.settings.presentation.components.tse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.TseSettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosTextButton

/**
 * Confirmation dialog for applying bundled firmware update.
 *
 * Warns about risks: power loss during update can permanently damage TSE.
 *
 * @param language Current language for localization
 * @param onConfirm Callback when user confirms firmware apply
 * @param onDismiss Callback when dialog is dismissed
 */
@Composable
fun TseFirmwareApplyDialog(
    language: Language,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    PosDialog(
        onDismiss = onDismiss,
        title = str(TseSettingsKeys.firmwareApplyDialogTitle),
        titleIcon = Icons.Default.Warning,
        footer = {
            PosTextButton(text = getString(CommonKeys.cancel, language), onClick = onDismiss)
            PosButton(
                text = str(TseSettingsKeys.firmwareApplyConfirmButton),
                icon = Icons.Default.SystemUpdateAlt,
                onClick = onConfirm,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                    ),
            )
        },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = str(TseSettingsKeys.firmwareApplyDialogMessage),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
