/**
 * TSE administration unlock dialog.
 *
 * The Admin PIN is deliberately not stored (Swissbit guidance manual 2.5), so an operator
 * supplies it once per sitting before any administrative action. Nothing unattended needs it:
 * signing, the scheduled self test and time sync all run without it, so this prompt only ever
 * appears in front of a deliberate administrative act.
 *
 * @since 2.0.0
 */
package org.example.project.features.settings.presentation.components.tse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.TseSettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.keyboardAwarePadding
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.PosTextField

/**
 * Prompts for the Admin PIN that starts a TSE administration sitting.
 *
 * @param language Current language for localization
 * @param pin Current PIN input
 * @param isUnlocking True while the unlock request is in flight
 * @param onPinChange Callback when the PIN input changes
 * @param onUnlock Callback when the operator confirms
 * @param onDismiss Callback when the dialog is dismissed
 */
@Composable
fun TseAdminUnlockDialog(
    language: Language,
    pin: String,
    isUnlocking: Boolean,
    onPinChange: (String) -> Unit,
    onUnlock: () -> Unit,
    onDismiss: () -> Unit,
) {
    PosDialog(
        onDismiss = onDismiss,
        title = str(TseSettingsKeys.adminUnlockTitle),
        titleIcon = Icons.Default.Lock,
        footer = {
            PosTextButton(text = getString(CommonKeys.cancel, language), onClick = onDismiss)
            PosButton(
                text = str(TseSettingsKeys.adminUnlockButton),
                onClick = onUnlock,
                enabled = pin.isNotBlank() && !isUnlocking,
            )
        },
    ) {
        Column(
            modifier = Modifier.keyboardAwarePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = str(TseSettingsKeys.adminUnlockDescription),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PosTextField(
                value = pin,
                onValueChange = onPinChange,
                label = str(TseSettingsKeys.adminUnlockPinLabel),
                modifier = Modifier.fillMaxWidth(),
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
                visualTransformation = PasswordVisualTransformation(),
                enabled = !isUnlocking,
            )
        }
    }
}
