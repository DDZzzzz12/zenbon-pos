/**
 * TSE commissioning dialog.
 *
 * Collects the Admin PIN, Admin PUK and credential seed for a single setup call. None of these
 * are stored (Swissbit guidance manual 2.5) and the seed is single-use — consumed by the call
 * it is entered for — which is exactly why they are gathered here at the moment of use rather
 * than living in the configuration form.
 *
 * @since 2.0.0
 */
package org.example.project.features.settings.presentation.components.tse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import org.example.project.core.utils.TseCredentialPlaceholders
import org.example.project.core.utils.keyboardAwarePadding
import org.example.project.features.settings.presentation.state.TseSetupTerminalOption
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosPickerField
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.PosTextField

/**
 * Prompts for the credentials that commission a TSE, then hands them to [onConfirm].
 *
 * The TimeAdmin PIN is asked for here too: setup writes it to the device with the Admin PIN, and a
 * shop that never configured one used to fail the config save behind a spinner that never ended.
 * Unlike the other three it is stored, so time sync can run unattended.
 *
 * @param language Current language for localization
 * @param isSubmitting True while the setup call is in flight
 * @param initialTimeAdminPin A TimeAdmin PIN already typed into the configuration form, if any
 * @param errorMessage Why the last attempt stopped; the dialog stays open so the operator can correct it
 * @param terminals Active fiscal terminals setup may register as the TSE's first client
 * @param isLoadingTerminals True while [terminals] is being fetched
 * @param selectedTerminalId The chosen terminal's client id; setup stays disabled while null
 * @param onSelectTerminal Callback with the client id the operator picked
 * @param onOpenTerminals Opens the Terminals screen from the empty state; null where there is none to open
 * @param onConfirm Callback with (adminPin, adminPuk, credentialSeed, timeAdminPin)
 * @param onDismiss Callback when the dialog is dismissed
 */
@Composable
fun TseSetupDialog(
    language: Language,
    isSubmitting: Boolean,
    initialTimeAdminPin: String,
    errorMessage: String?,
    terminals: List<TseSetupTerminalOption>,
    isLoadingTerminals: Boolean,
    selectedTerminalId: String?,
    onSelectTerminal: (String) -> Unit,
    onConfirm: (adminPin: String, adminPuk: String, credentialSeed: String, timeAdminPin: String) -> Unit,
    onDismiss: () -> Unit,
    onOpenTerminals: (() -> Unit)? = null,
) {
    var adminPin by remember { mutableStateOf("") }
    var adminPuk by remember { mutableStateOf("") }
    var timeAdminPin by remember { mutableStateOf(initialTimeAdminPin) }
    var credentialSeed by remember { mutableStateOf("") }

    // The server refuses these too (E2E round 4, R4-13); refusing here names the field before a
    // one-way setup is attempted at all.
    val pinIsPlaceholder = TseCredentialPlaceholders.isPlaceholderPin(adminPin)
    val pukIsPlaceholder = TseCredentialPlaceholders.isPlaceholderPuk(adminPuk)
    val timeAdminPinIsPlaceholder = TseCredentialPlaceholders.isPlaceholderPin(timeAdminPin)
    val canSubmit =
        adminPin.isNotBlank() &&
            adminPuk.isNotBlank() &&
            timeAdminPin.isNotBlank() &&
            !pinIsPlaceholder &&
            !pukIsPlaceholder &&
            !timeAdminPinIsPlaceholder &&
            credentialSeed.isNotBlank() &&
            // Setup registers one terminal as the TSE's client; with none chosen there is nothing to register.
            selectedTerminalId != null &&
            !isSubmitting

    PosDialog(
        onDismiss = onDismiss,
        title = str(TseSettingsKeys.setupDialogTitle),
        titleIcon = Icons.Default.Build,
        footer = {
            PosTextButton(text = getString(CommonKeys.cancel, language), onClick = onDismiss)
            PosButton(
                text = str(TseSettingsKeys.setupTse),
                onClick = { onConfirm(adminPin, adminPuk, credentialSeed, timeAdminPin) },
                enabled = canSubmit,
                isLoading = isSubmitting,
            )
        },
    ) {
        // No verticalScroll here: PosDialog's body already scrolls, and a nested scroll container
        // is measured with infinite height and throws (the dialog never opened on the wasm till).
        Column(
            modifier = Modifier.keyboardAwarePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = str(TseSettingsKeys.setupDialogDescription),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SetupTerminalSection(
                terminals = terminals,
                isLoading = isLoadingTerminals,
                selectedTerminalId = selectedTerminalId,
                enabled = !isSubmitting,
                onSelect = onSelectTerminal,
                onOpenTerminals = onOpenTerminals,
            )

            PosTextField(
                value = adminPin,
                onValueChange = { adminPin = it },
                label = str(TseSettingsKeys.adminPin),
                modifier = Modifier.fillMaxWidth(),
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Next,
                visualTransformation = PasswordVisualTransformation(),
                enabled = !isSubmitting,
                isError = pinIsPlaceholder,
                errorMessage = if (pinIsPlaceholder) str(TseSettingsKeys.placeholderCredential) else null,
            )
            PosTextField(
                value = adminPuk,
                onValueChange = { adminPuk = it },
                label = str(TseSettingsKeys.adminPuk),
                modifier = Modifier.fillMaxWidth(),
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Next,
                visualTransformation = PasswordVisualTransformation(),
                enabled = !isSubmitting,
                isError = pukIsPlaceholder,
                errorMessage = if (pukIsPlaceholder) str(TseSettingsKeys.placeholderCredential) else null,
            )
            PosTextField(
                value = timeAdminPin,
                onValueChange = { timeAdminPin = it },
                label = str(TseSettingsKeys.timeAdminPin),
                modifier = Modifier.fillMaxWidth(),
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Next,
                visualTransformation = PasswordVisualTransformation(),
                enabled = !isSubmitting,
                isError = timeAdminPinIsPlaceholder,
                errorMessage = if (timeAdminPinIsPlaceholder) str(TseSettingsKeys.placeholderCredential) else null,
            )
            Text(
                text = str(TseSettingsKeys.setupTimeAdminPinNote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PosTextField(
                value = credentialSeed,
                onValueChange = { credentialSeed = it },
                label = str(TseSettingsKeys.credentialSeed),
                modifier = Modifier.fillMaxWidth(),
                imeAction = ImeAction.Done,
                visualTransformation = PasswordVisualTransformation(),
                enabled = !isSubmitting,
            )
            errorMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * The terminal setup registers as the TSE's first client: a picker over the active fiscal terminals,
 * or — when there is none — the sentence saying one must be created or paired first.
 */
@Composable
private fun SetupTerminalSection(
    terminals: List<TseSetupTerminalOption>,
    isLoading: Boolean,
    selectedTerminalId: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onOpenTerminals: (() -> Unit)?,
) {
    when {
        isLoading ->
            Text(
                text = str(TseSettingsKeys.setupTerminalsLoading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        terminals.isEmpty() ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = str(TseSettingsKeys.setupNoTerminal),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                onOpenTerminals?.let { open -> PosSecondaryButton(text = str(TseSettingsKeys.setupOpenTerminals), onClick = open) }
            }
        else -> {
            var expanded by remember { mutableStateOf(false) }
            val selected = terminals.firstOrNull { it.clientId == selectedTerminalId }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box {
                    PosPickerField(
                        value = selected?.displayName.orEmpty(),
                        onClick = { if (enabled) expanded = true },
                        label = str(TseSettingsKeys.setupTerminalLabel),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled,
                    )
                    PosDropdown(expanded = expanded, onDismissRequest = { expanded = false }) {
                        terminals.forEach { option ->
                            PosDropdownItem(
                                text = option.displayName,
                                selected = option.clientId == selectedTerminalId,
                                onClick = {
                                    onSelect(option.clientId)
                                    expanded = false
                                },
                            )
                        }
                    }
                }
                Text(
                    text = str(TseSettingsKeys.setupTerminalNote),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
