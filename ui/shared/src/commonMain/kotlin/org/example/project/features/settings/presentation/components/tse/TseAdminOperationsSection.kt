/**
 * TSE admin operations section component.
 *
 * Features collapsible subsections for clients, transactions, and last transaction
 * that load data on expand to reduce initial load.
 *
 * @since 2.0.0
 */
package org.example.project.features.settings.presentation.components.tse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.TseSettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.isCompact
import org.example.project.features.settings.presentation.components.common.SettingsSectionHeader
import org.example.project.ui.components.PosAutoSizeText
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosDangerOutlinedButton
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosStatusChip
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.theme.PosSpacing
import kotlin.time.Instant

/**
 * TSE admin operations section with operation buttons and collapsible subsections
 * for registered clients, open transactions, and last transaction.
 *
 * Subsections are collapsed by default and load data when expanded.
 *
 * @param language Current language for localization
 * @param canPerform Whether operations can be performed
 * @param isPerforming Whether an operation is currently in progress
 * @param isTseAvailable Whether TSE is available
 * @param isDevelopmentFirmware Whether TSE is running development firmware (enables factory reset)
 * @param clients List of registered client IDs
 * @param isLoadingClients Whether clients are being loaded
 * @param openTransactions List of open transaction numbers
 * @param isLoadingTransactions Whether transactions are being loaded
 * @param lastTransaction Last transaction response data
 * @param isLoadingLastTransaction Whether last transaction is being loaded
 * @param onSelfTest Callback for self-test operation
 * @param onSyncTime Callback for time sync operation
 * @param onHealthCheck Callback for health check operation
 * @param onFlashHealth Callback for flash health operation
 * @param onExport Callback for export operation
 * @param onFirmwareCheck Callback for firmware update check
 * @param onFactoryReset Callback for factory reset operation (development TSE only)
 * @param onRefreshClients Callback to refresh clients list
 * @param onRefreshTransactions Callback to refresh transactions list
 * @param onRefreshLastTransaction Callback to refresh last transaction
 * @param firmwareUpdateCheck Last firmware update check result
 * @param isCheckingFirmware Whether firmware check is in progress
 */
@Composable
fun TseAdminOperationsSection(
    language: Language,
    canPerform: Boolean,
    isPerforming: Boolean,
    isTseAvailable: Boolean,
    isDevelopmentFirmware: Boolean,
    clients: List<String>,
    isLoadingClients: Boolean,
    openTransactions: List<Long>,
    isLoadingTransactions: Boolean,
    lastTransaction: TseDto.LastTransactionResultDto?,
    isLoadingLastTransaction: Boolean,
    firmwareUpdateCheck: TseDto.FirmwareUpdateCheckResultDto?,
    isCheckingFirmware: Boolean,
    /** Whether an admin sitting is live — the Admin PIN is never stored, so it can lapse. */
    isAdminUnlocked: Boolean,
    onLockAdmin: () -> Unit,
    onUnlockAdmin: () -> Unit,
    onSelfTest: () -> Unit,
    onSyncTime: () -> Unit,
    onHealthCheck: () -> Unit,
    onFlashHealth: () -> Unit,
    onFirmwareCheck: () -> Unit,
    onFactoryReset: () -> Unit,
    onDecommission: () -> Unit = {},
    onApplyBundledFirmware: () -> Unit = {},
    onDisableCspExport: () -> Unit = {},
    onExportLcm: () -> Unit = {},
    onRefreshClients: () -> Unit,
    onRefreshTransactions: () -> Unit,
    onRefreshLastTransaction: () -> Unit,
    windowSize: WindowSizeClass,
    phoneLayout: Boolean = false,
) {
    // Expand states for collapsible sections
    var isClientsExpanded by remember { mutableStateOf(false) }
    var isTransactionsExpanded by remember { mutableStateOf(false) }
    var isLastTransactionExpanded by remember { mutableStateOf(false) }

    // Load data when sections are expanded
    LaunchedEffect(isClientsExpanded) {
        if (isClientsExpanded && clients.isEmpty() && !isLoadingClients) {
            onRefreshClients()
        }
    }

    LaunchedEffect(isTransactionsExpanded) {
        if (isTransactionsExpanded && openTransactions.isEmpty() && !isLoadingTransactions) {
            onRefreshTransactions()
        }
    }

    LaunchedEffect(isLastTransactionExpanded) {
        if (isLastTransactionExpanded && lastTransaction == null && !isLoadingLastTransaction) {
            onRefreshLastTransaction()
        }
    }

    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Plain,
    ) {
        body {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsSectionHeader(
                    title = str(TseSettingsKeys.adminOperationsSection),
                    icon = Icons.Default.AdminPanelSettings,
                )

                // Whether the Admin PIN is currently held. Worth showing: nothing here prompts
                // until an action is refused, so without it an operator cannot tell whether the
                // next click will ask for a PIN.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PosStatusChip(
                        text =
                            if (isAdminUnlocked) {
                                str(TseSettingsKeys.adminSessionUnlocked)
                            } else {
                                str(TseSettingsKeys.adminSessionLocked)
                            },
                        backgroundColor =
                            if (isAdminUnlocked) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        textColor =
                            if (isAdminUnlocked) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        icon = if (isAdminUnlocked) Icons.Default.LockOpen else Icons.Default.Lock,
                    )

                    if (isAdminUnlocked) {
                        PosTextButton(
                            text = str(TseSettingsKeys.adminLockButton),
                            onClick = onLockAdmin,
                        )
                    } else {
                        // A deliberate way in, rather than only stumbling onto the prompt by
                        // triggering an operation that happens to need it.
                        PosTextButton(
                            text = str(TseSettingsKeys.adminUnlockButton),
                            onClick = onUnlockAdmin,
                        )
                    }
                }

                HorizontalDivider()

                // Admin operation buttons
                if (isPerforming) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    val isCompact = windowSize.isCompact()

                    if (isCompact) {
                        // Compact: one button per row
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PosSecondaryButton(
                                text = str(TseSettingsKeys.runSelfTest),
                                onClick = onSelfTest,
                                enabled = canPerform,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            PosSecondaryButton(
                                text = str(TseSettingsKeys.syncTime),
                                onClick = onSyncTime,
                                enabled = canPerform,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            PosSecondaryButton(
                                text = str(TseSettingsKeys.healthCheck),
                                onClick = onHealthCheck,
                                enabled = canPerform,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            PosSecondaryButton(
                                text = str(TseSettingsKeys.flashHealth),
                                onClick = onFlashHealth,
                                enabled = canPerform,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            PosSecondaryButton(
                                text = str(TseSettingsKeys.firmwareCheck),
                                onClick = onFirmwareCheck,
                                enabled = canPerform && !isCheckingFirmware,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        // Non-compact: 2 buttons per row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PosSecondaryButton(
                                text = str(TseSettingsKeys.runSelfTest),
                                onClick = onSelfTest,
                                enabled = canPerform,
                                modifier = Modifier.weight(1f),
                            )
                            PosSecondaryButton(
                                text = str(TseSettingsKeys.syncTime),
                                onClick = onSyncTime,
                                enabled = canPerform,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PosSecondaryButton(
                                text = str(TseSettingsKeys.healthCheck),
                                onClick = onHealthCheck,
                                enabled = canPerform,
                                modifier = Modifier.weight(1f),
                            )
                            PosSecondaryButton(
                                text = str(TseSettingsKeys.flashHealth),
                                onClick = onFlashHealth,
                                enabled = canPerform,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PosSecondaryButton(
                                text = str(TseSettingsKeys.firmwareCheck),
                                onClick = onFirmwareCheck,
                                enabled = canPerform && !isCheckingFirmware,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    // Firmware Update Check Result
                    firmwareUpdateCheck?.let { result ->
                        FirmwareUpdateResultCard(language = language, result = result)
                    }

                    // Additional operations divider
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // CSP Export Disable button
                    PosSecondaryButton(
                        text = str(TseSettingsKeys.disableCspExport),
                        onClick = onDisableCspExport,
                        enabled = canPerform,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // LCM Export button
                    PosSecondaryButton(
                        text = str(TseSettingsKeys.exportLcm),
                        onClick = onExportLcm,
                        enabled = canPerform,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Apply Bundled Firmware button (only if bundled update is available)
                    if (firmwareUpdateCheck?.bundledUpdateAvailable == true) {
                        PosSecondaryButton(
                            text = str(TseSettingsKeys.applyBundledFirmware),
                            onClick = onApplyBundledFirmware,
                            enabled = canPerform,
                            modifier = Modifier.fillMaxWidth(),
                            icon = Icons.Default.SystemUpdateAlt,
                            colors =
                                ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.tertiary,
                                ),
                        )
                    }

                    // Factory Reset Button (Development TSE Only)
                    if (isDevelopmentFirmware) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            text = str(TseSettingsKeys.factoryResetWarning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(4.dp))
                        PosDangerOutlinedButton(
                            text = "${str(TseSettingsKeys.factoryReset)} ${str(TseSettingsKeys.developmentOnly)}",
                            onClick = onFactoryReset,
                            enabled = canPerform,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // Decommission Button (DANGER)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        text = str(TseSettingsKeys.decommissionWarning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                    PosDangerOutlinedButton(
                        text = str(TseSettingsKeys.decommission),
                        onClick = onDecommission,
                        enabled = canPerform,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Collapsible subsections (only if TSE available)
                if (isTseAvailable) {
                    // ============================================
                    // Registered Clients Section (Collapsible)
                    // ============================================
                    HorizontalDivider()

                    CollapsibleSectionHeader(
                        icon = Icons.Default.Devices,
                        title = str(TseSettingsKeys.registeredClientsSection),
                        count = clients.size,
                        isExpanded = isClientsExpanded,
                        isLoading = isLoadingClients,
                        onToggle = { isClientsExpanded = !isClientsExpanded },
                        onRefresh = onRefreshClients,
                        language = language,
                        phoneLayout = phoneLayout,
                    )

                    AnimatedVisibility(
                        visible = isClientsExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        Column(modifier = Modifier.padding(start = 28.dp, top = 8.dp)) {
                            if (isLoadingClients) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                }
                            } else if (clients.isEmpty()) {
                                Text(
                                    text = str(TseSettingsKeys.noClients),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                clients.forEach { client ->
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Computer,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(client, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }

                    // ============================================
                    // Open Transactions Section (Collapsible)
                    // ============================================
                    HorizontalDivider()

                    CollapsibleSectionHeader(
                        icon = Icons.Default.Receipt,
                        title = str(TseSettingsKeys.openTransactionsSection),
                        count = openTransactions.size,
                        isExpanded = isTransactionsExpanded,
                        isLoading = isLoadingTransactions,
                        onToggle = { isTransactionsExpanded = !isTransactionsExpanded },
                        onRefresh = onRefreshTransactions,
                        language = language,
                        phoneLayout = phoneLayout,
                    )

                    AnimatedVisibility(
                        visible = isTransactionsExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        Column(modifier = Modifier.padding(start = 28.dp, top = 8.dp)) {
                            if (isLoadingTransactions) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                }
                            } else if (openTransactions.isEmpty()) {
                                Text(
                                    text = str(TseSettingsKeys.noOpenTransactions),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                openTransactions.forEach { txn ->
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Receipt,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text = "${str(TseSettingsKeys.transaction)} #$txn",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ============================================
                    // Last Transaction Section (Collapsible)
                    // ============================================
                    HorizontalDivider()

                    CollapsibleSectionHeader(
                        icon = Icons.Default.History,
                        title = str(TseSettingsKeys.lastTransactionSection),
                        count = null, // No count for last transaction
                        isExpanded = isLastTransactionExpanded,
                        isLoading = isLoadingLastTransaction,
                        onToggle = { isLastTransactionExpanded = !isLastTransactionExpanded },
                        onRefresh = onRefreshLastTransaction,
                        language = language,
                        phoneLayout = phoneLayout,
                    )

                    AnimatedVisibility(
                        visible = isLastTransactionExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        Column(modifier = Modifier.padding(start = 28.dp, top = 8.dp)) {
                            if (isLoadingLastTransaction) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                }
                            } else if (lastTransaction == null) {
                                Text(
                                    text = str(TseSettingsKeys.noLastTransaction),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    LastTransactionInfoRow(
                                        label = str(TseSettingsKeys.transactionNumber),
                                        value = "#${lastTransaction.transactionNumber}",
                                    )
                                    LastTransactionInfoRow(
                                        label = str(TseSettingsKeys.signatureCounter),
                                        value = lastTransaction.signatureCounter.toString(),
                                    )
                                    LastTransactionInfoRow(
                                        label = str(TseSettingsKeys.logTime),
                                        value = formatTimestamp(lastTransaction.logTime),
                                    )
                                    LastTransactionInfoRow(
                                        label = str(TseSettingsKeys.serialNumber),
                                        value = lastTransaction.serialNumberHex.take(16) + "...",
                                        fullValue = lastTransaction.serialNumberHex,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Displays firmware update check result with status indicators.
 */
@Composable
private fun FirmwareUpdateResultCard(
    language: Language,
    result: TseDto.FirmwareUpdateCheckResultDto,
) {
    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Tonal,
        containerColor =
            if (result.updateAvailable) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
    ) {
        body {
            Column(
                verticalArrangement = Arrangement.spacedBy(PosSpacing.sm),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector =
                            if (result.updateAvailable) {
                                Icons.Default.Warning
                            } else {
                                Icons.Default.CheckCircle
                            },
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint =
                            if (result.updateAvailable) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                    )
                    Text(
                        text =
                            if (result.updateAvailable) {
                                str(TseSettingsKeys.firmwareUpdateAvailable)
                            } else {
                                str(TseSettingsKeys.firmwareUpToDate)
                            },
                        style = MaterialTheme.typography.titleSmall,
                        color =
                            if (result.updateAvailable) {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }

                if (result.bundledUpdateAvailable && result.bundledVersion != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "${str(TseSettingsKeys.bundledUpdate)}: ${result.bundledVersion}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                if (result.downloadUrl != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PosAutoSizeText(
                            text = "${str(TseSettingsKeys.onlineUpdate)}: ${result.downloadUrl}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Collapsible section header with expand/collapse toggle and refresh button.
 */
@Composable
private fun CollapsibleSectionHeader(
    icon: ImageVector,
    title: String,
    count: Int?,
    isExpanded: Boolean,
    isLoading: Boolean,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
    language: Language,
    phoneLayout: Boolean,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (phoneLayout) Modifier.heightIn(min = 48.dp) else Modifier)
                .clickable(onClick = onToggle),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = if (count != null) "$title ($count)" else title,
                style = MaterialTheme.typography.titleSmall,
            )
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Only show refresh button when expanded
        if (isExpanded) {
            PosIconButton(
                icon = Icons.Default.Refresh,
                contentDescription = getString(CommonKeys.refresh, language),
                onClick = onRefresh,
                enabled = !isLoading,
                size = if (phoneLayout) 48.dp else 40.dp,
                iconSize = 20.dp,
            )
        }
    }
}

/**
 * Info row for last transaction details.
 */
@Composable
private fun LastTransactionInfoRow(
    label: String,
    value: String,
    fullValue: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PosAutoSizeText(
            text = value,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Format Unix timestamp to readable date/time string.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
private fun formatTimestamp(unixSeconds: Long): String =
    try {
        val instant = Instant.fromEpochSeconds(unixSeconds)
        val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        "${localDateTime.date} ${localDateTime.hour.toString().padStart(
            2,
            '0',
        )}:${localDateTime.minute.toString().padStart(2, '0')}:${localDateTime.second.toString().padStart(2, '0')}"
    } catch (e: Exception) {
        unixSeconds.toString()
    }
