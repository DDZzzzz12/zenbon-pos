/**
 * TSE Decommission confirmation dialog.
 *
 * Multi-step confirmation dialog requiring the user to type "DECOMMISSION"
 * before the irreversible decommission operation can proceed.
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.settings.presentation.components.tse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.TseSettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.keyboardAwarePadding
import org.example.project.ui.components.PosDangerButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.PosTextField

/**
 * Confirmation dialog for TSE decommission operation.
 *
 * Requires the user to type "DECOMMISSION" exactly to enable the confirm button.
 *
 * @param language Current language for localization
 * @param confirmText Current text in the confirmation input field
 * @param onConfirmTextChange Callback when confirmation text changes
 * @param onConfirm Callback when user confirms decommission
 * @param onDismiss Callback when dialog is dismissed
 */
@Composable
fun TseDecommissionDialog(
    language: Language,
    confirmText: String,
    onConfirmTextChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val canConfirm = confirmText == "DECOMMISSION"

    PosDialog(
        onDismiss = onDismiss,
        title = str(TseSettingsKeys.decommissionDialogTitle),
        titleIcon = Icons.Default.Warning,
        footer = {
            PosTextButton(text = getString(CommonKeys.cancel, language), onClick = onDismiss)
            PosDangerButton(
                text = str(TseSettingsKeys.decommissionConfirmButton),
                onClick = onConfirm,
                enabled = canConfirm,
            )
        },
    ) {
        // No verticalScroll here: PosDialog's body already scrolls (see TseSetupDialog).
        Column(
            modifier = Modifier.keyboardAwarePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = str(TseSettingsKeys.decommissionDialogMessage),
                style = MaterialTheme.typography.bodyMedium,
            )

            PosTextField(
                value = confirmText,
                onValueChange = onConfirmTextChange,
                label = str(TseSettingsKeys.decommissionConfirmHint),
                isError = confirmText.isNotEmpty() && !canConfirm,
                modifier = Modifier.fillMaxWidth(),
                imeAction = ImeAction.Done,
            )
        }
    }
}
