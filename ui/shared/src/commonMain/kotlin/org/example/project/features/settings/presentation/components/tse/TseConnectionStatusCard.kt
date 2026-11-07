/**
 * TSE connection status card component.
 *
 * @since 2.0.0
 */
package org.example.project.features.settings.presentation.components.tse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.TseSettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.enumStrings.localizedTseModeName
import org.example.project.features.settings.presentation.components.common.SettingsSectionHeader
import org.example.project.features.settings.presentation.state.TseSettingsUiState
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardStatus
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.theme.LocalPosColors

/**
 * Displays the TSE connection status with details and restart button.
 *
 * @param language Current language for localization
 * @param uiState TSE settings UI state containing status information
 * @param onRefresh Callback when refresh is requested
 * @param onRestart Callback when TSE restart is requested
 */
@Composable
fun TseConnectionStatusCard(
    language: Language,
    uiState: TseSettingsUiState,
    onRefresh: () -> Unit,
    onRestart: () -> Unit = {},
    phoneLayout: Boolean = false,
) {
    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Plain,
        status = if (uiState.isTseAvailable) PosCardStatus.Active else PosCardStatus.Error,
    ) {
        body {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsSectionHeader(
                        title = str(TseSettingsKeys.connectionStatusSection),
                        icon = Icons.Default.Cable,
                    )
                    PosIconButton(
                        icon = Icons.Default.Refresh,
                        contentDescription = getString(CommonKeys.refresh, language),
                        onClick = onRefresh,
                        size = if (phoneLayout) 48.dp else 40.dp,
                    )
                }

                HorizontalDivider()

                // Status indicator
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (uiState.isTseAvailable) Icons.Default.CheckCircle else Icons.Default.Cancel,
                        contentDescription = null,
                        tint = if (uiState.isTseAvailable) LocalPosColors.current.posAvailable else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text =
                            if (uiState.isTseAvailable) {
                                str(TseSettingsKeys.connected)
                            } else {
                                str(TseSettingsKeys.tseUnavailable)
                            },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

                // Status details
                uiState.status?.let { status ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Column {
                            Text(
                                text = str(TseSettingsKeys.mode),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                // Localized: the raw wire value read "Modus DISABLED" (QA admin19 NOTE-c).
                                text = localizedTseModeName(status.mode, LocalAppLanguage.current),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        status.lastError?.let { error ->
                            Column {
                                Text(
                                    text = str(TseSettingsKeys.lastError),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                // Credential status (visible when TSE is connected but not initialized)
                if (uiState.isTseAvailable && !uiState.isTseInitialized()) {
                    uiState.tseInfo?.let { info ->
                        HorizontalDivider()

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = str(TseSettingsKeys.credentialStatus),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }

                        val allChanged = info.hasChangedAdminPin && info.hasChangedPuk && info.hasChangedTimeAdminPin

                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            CredentialStatusRow(
                                label = str(TseSettingsKeys.adminPinChanged),
                                changed = info.hasChangedAdminPin,
                            )
                            CredentialStatusRow(
                                label = str(TseSettingsKeys.pukChanged),
                                changed = info.hasChangedPuk,
                            )
                            CredentialStatusRow(
                                label = str(TseSettingsKeys.timeAdminPinChanged),
                                changed = info.hasChangedTimeAdminPin,
                            )
                        }

                        if (!allChanged) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = str(TseSettingsKeys.credentialSetupRequired),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                // Restart button (visible when TSE is not available)
                if (!uiState.isTseAvailable) {
                    HorizontalDivider()
                    Text(
                        text = str(TseSettingsKeys.restartTseDescription),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PosButton(
                        text = str(TseSettingsKeys.restartTse),
                        onClick = onRestart,
                        enabled = !uiState.isPerformingOperation,
                        modifier = Modifier.fillMaxWidth(),
                        isLoading = uiState.isPerformingOperation,
                    )
                }
            }
        }
    }
}

@Composable
private fun CredentialStatusRow(
    label: String,
    changed: Boolean,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (changed) Icons.Default.CheckCircle else Icons.Default.Cancel,
            contentDescription = null,
            tint = if (changed) LocalPosColors.current.posAvailable else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
