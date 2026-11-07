/**
 * TSE configuration section component.
 *
 * @since 2.0.0
 */
package org.example.project.features.settings.presentation.components.tse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseFailureBehavior
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.TseSettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.isCompact
import org.example.project.features.settings.presentation.components.common.SettingsSectionHeader
import org.example.project.features.settings.presentation.state.TseConfigFormState
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosPickerField
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.PosTextField

/**
 * TSE configuration form section.
 *
 * @param language Current language for localization
 * @param formState Current form state
 * @param isEditing Whether the form is in edit mode
 * @param isSaving Whether the form is being saved
 * @param canEdit Whether the user can edit the configuration
 * @param isTseInitialized Whether TSE has been initialized (locks PIN/PUK fields)
 * @param offerSetup Whether "TSE initialisieren" is offered ([TseSettingsUiState.canOfferSetup])
 * @param isPerformingOperation Whether an operation is currently in progress
 * @param onEdit Callback when edit mode is requested
 * @param onSave Callback when save is requested
 * @param onCancel Callback when cancel is requested
 * @param onFieldChange Callback for field value changes
 * @param onKeepAliveChange Callback for keep-alive toggle changes
 * @param onSetup Callback when setup is requested for uninitialized TSE
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TseConfigurationSection(
    language: Language,
    formState: TseConfigFormState,
    isEditing: Boolean,
    isSaving: Boolean,
    canEdit: Boolean,
    isTseInitialized: Boolean,
    offerSetup: Boolean = false,
    isPerformingOperation: Boolean = false,
    onEdit: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onFieldChange: (String, String) -> Unit,
    onKeepAliveChange: (Boolean) -> Unit,
    onSetup: () -> Unit = {},
    windowSize: WindowSizeClass,
) {
    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Plain,
    ) {
        body {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (windowSize.isCompact()) {
                    // Compact: stack header and action buttons vertically
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SettingsSectionHeader(
                            title = str(TseSettingsKeys.configurationSection),
                            icon = Icons.Default.Settings,
                        )
                        if (canEdit) {
                            if (isEditing) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PosSecondaryButton(
                                        text = getString(CommonKeys.cancel, language),
                                        onClick = onCancel,
                                        enabled = !isSaving,
                                    )
                                    PosButton(
                                        text = getString(CommonKeys.save, language),
                                        onClick = onSave,
                                        enabled = !isSaving,
                                        isLoading = isSaving,
                                    )
                                }
                            } else {
                                PosButton(
                                    text = getString(CommonKeys.edit, language),
                                    onClick = onEdit,
                                )
                            }
                        }
                    }
                } else {
                    // Non-compact: header and buttons in a single row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SettingsSectionHeader(
                            title = str(TseSettingsKeys.configurationSection),
                            icon = Icons.Default.Settings,
                        )
                        if (canEdit) {
                            if (isEditing) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PosSecondaryButton(
                                        text = getString(CommonKeys.cancel, language),
                                        onClick = onCancel,
                                        enabled = !isSaving,
                                    )
                                    PosButton(
                                        text = getString(CommonKeys.save, language),
                                        onClick = onSave,
                                        enabled = !isSaving,
                                        isLoading = isSaving,
                                    )
                                }
                            } else {
                                PosButton(
                                    text = getString(CommonKeys.edit, language),
                                    onClick = onEdit,
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                // Configuration fields — stack vertically on compact
                val isCompact = windowSize.isCompact()

                @Composable
                fun ModeDropdown(modifier: Modifier) {
                    var modeExpanded by remember { mutableStateOf(false) }
                    val currentMode =
                        try {
                            TseMode.valueOf(formState.mode)
                        } catch (_: Exception) {
                            TseMode.DISABLED
                        }
                    Box(modifier) {
                        PosPickerField(
                            value =
                                when (currentMode) {
                                    TseMode.NORMAL -> str(TseSettingsKeys.modeNormal)
                                    TseMode.SIMULATION -> str(TseSettingsKeys.modeSimulation)
                                    TseMode.DISABLED -> str(TseSettingsKeys.modeDisabled)
                                },
                            onClick = { if (isEditing) modeExpanded = true },
                            label = str(TseSettingsKeys.tseMode),
                            readOnly = !isEditing,
                        )
                        PosDropdown(
                            expanded = modeExpanded && isEditing,
                            onDismissRequest = { modeExpanded = false },
                        ) {
                            TseMode.entries.forEach { mode ->
                                PosDropdownItem(
                                    text =
                                        when (mode) {
                                            TseMode.NORMAL -> str(TseSettingsKeys.modeNormal)
                                            TseMode.SIMULATION -> str(TseSettingsKeys.modeSimulation)
                                            TseMode.DISABLED -> str(TseSettingsKeys.modeDisabled)
                                        },
                                    selected = mode == currentMode,
                                    onClick = {
                                        onFieldChange("mode", mode.name)
                                        modeExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }

                ModeDropdown(modifier = Modifier.fillMaxWidth())

                // Warning when TSE is initialized (PIN/PUK can't be changed afterwards).
                if (isTseInitialized) {
                    PosCard(
                        modifier = Modifier.fillMaxWidth(),
                        variant = PosCardVariant.Tonal,
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        body {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                )
                                Text(
                                    text = str(TseSettingsKeys.pinLockedWarning),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            }
                        }
                    }
                }

                // Stored TSE credentials — always available behind a reveal button (sensitive),
                // regardless of whether the TSE is located/connected/disabled. Editable only when
                // the TSE is NOT initialized and the user is in edit mode (the server rejects
                // PIN/PUK changes on an initialized TSE); read-only otherwise. Each field keeps its
                // own show/hide eye toggle.
                val credentialsEditable = !isTseInitialized && canEdit && isEditing
                var credentialsRevealed by remember { mutableStateOf(false) }
                if (!credentialsRevealed) {
                    PosSecondaryButton(
                        text = str(TseSettingsKeys.revealCredentials),
                        onClick = { credentialsRevealed = true },
                        icon = Icons.Default.Visibility,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    // Only the TimeAdmin PIN is stored (guidance manual 2.5). The Admin PIN, PUK
                    // and credential seed are not — they are entered in the setup dialog for a
                    // single commissioning call and never kept, so there is nothing to show or
                    // edit for them here.
                    TsePasswordField(
                        value = formState.timeAdminPin,
                        onValueChange = { if (credentialsEditable) onFieldChange("timeAdminPin", it) },
                        label = str(TseSettingsKeys.timeAdminPin),
                        enabled = true,
                        readOnly = !credentialsEditable,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // The server never returns the stored TimeAdmin PIN (T9-14), so the field
                    // starts empty; an empty field on save keeps the stored PIN.
                    Text(
                        text = str(TseSettingsKeys.timeAdminPinNotDisplayedNote),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = str(TseSettingsKeys.adminCredentialsNotStoredNote),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PosTextButton(
                        text = str(TseSettingsKeys.hideCredentials),
                        onClick = { credentialsRevealed = false },
                    )
                }

                // Keep-alive settings — stack vertically on compact
                if (isCompact) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = formState.keepAliveEnabled,
                                onCheckedChange = { onKeepAliveChange(it) },
                                enabled = isEditing,
                            )
                            Text(str(TseSettingsKeys.keepAliveEnabled))
                        }
                        PosTextField(
                            value = formState.keepAliveIntervalSeconds,
                            onValueChange = { onFieldChange("keepAliveIntervalSeconds", it) },
                            label = str(TseSettingsKeys.keepAliveInterval),
                            readOnly = !(isEditing && formState.keepAliveEnabled),
                            locked = true,
                            modifier = Modifier.fillMaxWidth(),
                            imeAction = ImeAction.Done,
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                        ) {
                            Checkbox(
                                checked = formState.keepAliveEnabled,
                                onCheckedChange = { onKeepAliveChange(it) },
                                enabled = isEditing,
                            )
                            Text(str(TseSettingsKeys.keepAliveEnabled))
                        }
                        PosTextField(
                            value = formState.keepAliveIntervalSeconds,
                            onValueChange = { onFieldChange("keepAliveIntervalSeconds", it) },
                            label = str(TseSettingsKeys.keepAliveInterval),
                            readOnly = !(isEditing && formState.keepAliveEnabled),
                            locked = true,
                            modifier = Modifier.weight(1f),
                            imeAction = ImeAction.Done,
                        )
                    }
                }

                // Failure Behavior dropdown
                var failureBehaviorExpanded by remember { mutableStateOf(false) }
                val currentBehavior =
                    try {
                        TseFailureBehavior.valueOf(formState.failureBehavior)
                    } catch (_: Exception) {
                        TseFailureBehavior.QUEUE
                    }
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth()) {
                        PosPickerField(
                            value =
                                when (currentBehavior) {
                                    TseFailureBehavior.QUEUE -> str(TseSettingsKeys.failureBehaviorQueue)
                                    TseFailureBehavior.FAIL -> str(TseSettingsKeys.failureBehaviorFail)
                                },
                            onClick = { if (isEditing) failureBehaviorExpanded = true },
                            label = str(TseSettingsKeys.failureBehavior),
                            readOnly = !isEditing,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        PosDropdown(
                            expanded = failureBehaviorExpanded && isEditing,
                            onDismissRequest = { failureBehaviorExpanded = false },
                        ) {
                            TseFailureBehavior.entries.forEach { behavior ->
                                PosDropdownItem(
                                    text =
                                        when (behavior) {
                                            TseFailureBehavior.QUEUE -> str(TseSettingsKeys.failureBehaviorQueue)
                                            TseFailureBehavior.FAIL -> str(TseSettingsKeys.failureBehaviorFail)
                                        },
                                    supportingText =
                                        when (behavior) {
                                            TseFailureBehavior.QUEUE -> str(TseSettingsKeys.failureBehaviorQueueDesc)
                                            TseFailureBehavior.FAIL -> str(TseSettingsKeys.failureBehaviorFailDesc)
                                        },
                                    selected = behavior == currentBehavior,
                                    onClick = {
                                        onFieldChange("failureBehavior", behavior.name)
                                        failureBehaviorExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    // Info card about current behavior
                    PosCard(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        variant = PosCardVariant.Plain,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ) {
                        body {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text =
                                        when (currentBehavior) {
                                            TseFailureBehavior.QUEUE -> str(TseSettingsKeys.failureBehaviorQueueDesc)
                                            TseFailureBehavior.FAIL -> str(TseSettingsKeys.failureBehaviorFailDesc)
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // Setup button while the TSE is not commissioned — also in DISABLED, where it cannot answer
                if (offerSetup && canEdit) {
                    // Credentials are entered/edited via the reveal-gated section above (available in
                    // all states); this block hosts only the device setup action.
                    PosCard(
                        modifier = Modifier.fillMaxWidth(),
                        variant = PosCardVariant.Tonal,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        body {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Build,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = str(TseSettingsKeys.setupTseDescription),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                                PosButton(
                                    text = str(TseSettingsKeys.setupTse),
                                    onClick = onSetup,
                                    enabled = !isPerformingOperation,
                                    modifier = Modifier.fillMaxWidth(),
                                    isLoading = isPerformingOperation,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Password field with visibility toggle.
 *
 * @param value Current field value
 * @param onValueChange Callback for value changes
 * @param label Field label
 * @param enabled Whether the field is editable
 * @param modifier Modifier for the field
 */
@Composable
fun TsePasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
) {
    var visible by remember { mutableStateOf(false) }

    PosTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        enabled = enabled,
        readOnly = readOnly,
        locked = readOnly,
        modifier = modifier,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            PosIconButton(
                icon = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = if (visible) "Hide" else "Show",
                onClick = { visible = !visible },
            )
        },
    )
}
