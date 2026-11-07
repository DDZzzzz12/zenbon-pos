/**
 * TSE queue monitoring section component.
 *
 * @since 2.0.0
 */
package org.example.project.features.settings.presentation.components.tse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import org.example.project.core.domain.dto.tse.TseSigningQueueDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.TseSettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.isCompact
import org.example.project.features.settings.presentation.components.common.SettingsSectionHeader
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardStatus
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownAnchor
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.theme.LocalPosColors
import org.example.project.ui.theme.PosSpacing

/**
 * TSE signing queue monitoring section with status overview,
 * control buttons, and expandable entries list.
 *
 * @param language Current language for localization
 * @param queueStatus Current queue status data
 * @param queueEntries List of queue entries
 * @param isLoadingStatus Whether status is being loaded
 * @param isLoadingEntries Whether entries are being loaded
 * @param isExpanded Whether the entries section is expanded
 * @param currentFilter Current status filter
 * @param isPerforming Whether an operation is in progress
 * @param onToggleExpand Callback to toggle entries expansion
 * @param onRefresh Callback to refresh queue data
 * @param onFilterChange Callback when filter changes
 * @param onPause Callback to pause service
 * @param onResume Callback to resume service
 * @param onTriggerRetry Callback to trigger manual retry
 * @param onCleanup Callback to cleanup old entries
 * @param onRetryEntry Callback to retry a specific entry
 * @param onCancelEntry Callback to abandon signing for an entry, with the operator's reason
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TseQueueMonitoringSection(
    language: Language,
    queueStatus: TseSigningQueueDto.QueueStatusDto?,
    queueEntries: List<TseSigningQueueDto.QueueEntryResponseDto>,
    isLoadingStatus: Boolean,
    isLoadingEntries: Boolean,
    isExpanded: Boolean,
    currentFilter: TseSigningStatus?,
    isPerforming: Boolean,
    onToggleExpand: () -> Unit,
    onRefresh: () -> Unit,
    onFilterChange: (TseSigningStatus?) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onTriggerRetry: () -> Unit,
    onCleanup: () -> Unit,
    onRetryEntry: (Long) -> Unit,
    onCancelEntry: (Long, String) -> Unit,
    windowSize: WindowSizeClass,
    phoneLayout: Boolean = false,
) {
    val buttonSize = if (phoneLayout) 48.dp else 40.dp
    // Abandoning a signature is a fiscal act, not a dismissal: the reason is collected first and
    // travels to the audit log. Null = no entry pending confirmation.
    var cancelTarget by remember { mutableStateOf<Long?>(null) }

    cancelTarget?.let { targetId ->
        CancelQueueEntryDialog(
            entryId = targetId,
            isPerforming = isPerforming,
            onDismiss = { cancelTarget = null },
            onConfirm = { reason ->
                cancelTarget = null
                onCancelEntry(targetId, reason)
            },
        )
    }

    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Plain,
    ) {
        body {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Header with expand/collapse. On a phone the buttons move under the title so a long
                // German title keeps the full width instead of breaking mid-word.
                val headerButtons: @Composable () -> Unit = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PosIconButton(
                            icon = Icons.Default.Refresh,
                            contentDescription = getString(CommonKeys.refresh, language),
                            onClick = onRefresh,
                            enabled = !isLoadingStatus,
                            size = buttonSize,
                        )
                        PosIconButton(
                            icon = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription =
                                if (isExpanded) {
                                    str(TseSettingsKeys.hideEntries)
                                } else {
                                    str(TseSettingsKeys.showEntries)
                                },
                            onClick = onToggleExpand,
                            size = buttonSize,
                        )
                    }
                }
                if (phoneLayout) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        SettingsSectionHeader(
                            title = str(TseSettingsKeys.signingQueueSection),
                            icon = Icons.Default.Queue,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            headerButtons()
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SettingsSectionHeader(
                            title = str(TseSettingsKeys.signingQueueSection),
                            icon = Icons.Default.Queue,
                            modifier = Modifier.weight(1f),
                        )
                        headerButtons()
                    }
                }

                HorizontalDivider()

                // Status overview
                if (queueStatus != null) {
                    // Service and TSE status indicators
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // Retry service status
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector =
                                    if (queueStatus.retryServiceRunning) {
                                        Icons.Default.PlayArrow
                                    } else {
                                        Icons.Default.Pause
                                    },
                                contentDescription = null,
                                tint =
                                    if (queueStatus.retryServiceRunning) {
                                        LocalPosColors.current.posAvailable
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text =
                                    if (queueStatus.retryServiceRunning) {
                                        str(TseSettingsKeys.serviceRunning)
                                    } else {
                                        str(TseSettingsKeys.servicePaused)
                                    },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        // TSE availability
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector =
                                    if (queueStatus.tseAvailable) {
                                        Icons.Default.CheckCircle
                                    } else {
                                        Icons.Default.Cancel
                                    },
                                contentDescription = null,
                                tint =
                                    if (queueStatus.tseAvailable) {
                                        LocalPosColors.current.posAvailable
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text =
                                    if (queueStatus.tseAvailable) {
                                        str(TseSettingsKeys.tseAvailableStatus)
                                    } else {
                                        str(TseSettingsKeys.tseUnavailableStatus)
                                    },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    // Status counts grid
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TseQueueStatusChip(
                            str(TseSettingsKeys.pendingCount),
                            queueStatus.pendingCount,
                            tseCountTone(failure = false, count = queueStatus.pendingCount),
                            Modifier.weight(1f),
                        )
                        TseQueueStatusChip(
                            str(TseSettingsKeys.processingCount),
                            queueStatus.processingCount,
                            tseCountTone(failure = false, count = queueStatus.processingCount),
                            Modifier.weight(1f),
                        )
                        TseQueueStatusChip(
                            str(TseSettingsKeys.queuedCount),
                            queueStatus.queuedCount,
                            tseCountTone(failure = false, count = queueStatus.queuedCount),
                            Modifier.weight(1f),
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TseQueueStatusChip(
                            str(TseSettingsKeys.signedCount),
                            queueStatus.signedCount,
                            tseCountTone(failure = false, count = queueStatus.signedCount),
                            Modifier.weight(1f),
                        )
                        TseQueueStatusChip(
                            str(TseSettingsKeys.failedCount),
                            queueStatus.failedCount,
                            tseCountTone(failure = true, count = queueStatus.failedCount),
                            Modifier.weight(1f),
                        )
                        TseQueueStatusChip(
                            str(TseSettingsKeys.cancelledCount),
                            queueStatus.cancelledCount,
                            tseCountTone(failure = false, count = queueStatus.cancelledCount),
                            Modifier.weight(1f),
                        )
                    }

                    // Oldest pending age
                    queueStatus.oldestPendingAge?.let { age ->
                        Text(
                            text = "${str(TseSettingsKeys.oldestPendingAge)}: $age",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Control buttons — stack vertically on compact
                    if (windowSize.isCompact()) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (queueStatus.retryServiceRunning) {
                                PosSecondaryButton(
                                    text = str(TseSettingsKeys.pauseService),
                                    onClick = onPause,
                                    enabled = !isPerforming,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                PosSecondaryButton(
                                    text = str(TseSettingsKeys.resumeService),
                                    onClick = onResume,
                                    enabled = !isPerforming,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            PosSecondaryButton(
                                text = str(TseSettingsKeys.triggerRetry),
                                onClick = onTriggerRetry,
                                enabled =
                                    !isPerforming &&
                                        queueStatus.tseAvailable &&
                                        (queueStatus.pendingCount > 0 || queueStatus.queuedCount > 0),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            PosSecondaryButton(
                                text = str(TseSettingsKeys.cleanupOldEntries),
                                onClick = onCleanup,
                                enabled = !isPerforming,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (queueStatus.retryServiceRunning) {
                                PosSecondaryButton(
                                    text = str(TseSettingsKeys.pauseService),
                                    onClick = onPause,
                                    enabled = !isPerforming,
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                PosSecondaryButton(
                                    text = str(TseSettingsKeys.resumeService),
                                    onClick = onResume,
                                    enabled = !isPerforming,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            PosSecondaryButton(
                                text = str(TseSettingsKeys.triggerRetry),
                                onClick = onTriggerRetry,
                                enabled =
                                    !isPerforming &&
                                        queueStatus.tseAvailable &&
                                        (queueStatus.pendingCount > 0 || queueStatus.queuedCount > 0),
                                modifier = Modifier.weight(1f),
                            )
                            PosSecondaryButton(
                                text = str(TseSettingsKeys.cleanupOldEntries),
                                onClick = onCleanup,
                                enabled = !isPerforming,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                } else if (isLoadingStatus) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                // Expandable entries section
                if (isExpanded && queueStatus != null) {
                    HorizontalDivider()

                    // Filter dropdown
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${str(TseSettingsKeys.queueEntries)} (${queueEntries.size})",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        var filterExpanded by remember { mutableStateOf(false) }
                        Box {
                            FilterChip(
                                selected = currentFilter != null,
                                onClick = { filterExpanded = true },
                                label = {
                                    Text(
                                        text = currentFilter?.name ?: str(TseSettingsKeys.allStatuses),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                            )
                            PosDropdown(
                                expanded = filterExpanded,
                                onDismissRequest = { filterExpanded = false },
                                anchor = PosDropdownAnchor.BottomEnd,
                            ) {
                                PosDropdownItem(
                                    text = str(TseSettingsKeys.allStatuses),
                                    selected = currentFilter == null,
                                    onClick = {
                                        onFilterChange(null)
                                        filterExpanded = false
                                    },
                                )
                                TseSigningStatus.entries.forEach { status ->
                                    PosDropdownItem(
                                        text = status.name,
                                        selected = status == currentFilter,
                                        onClick = {
                                            onFilterChange(status)
                                            filterExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }

                    // Entries list
                    if (isLoadingEntries) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    } else if (queueEntries.isEmpty()) {
                        Text(
                            text = str(TseSettingsKeys.noQueueEntries),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            queueEntries.take(20).forEach { entry ->
                                TseQueueEntryItem(
                                    language = language,
                                    entry = entry,
                                    isPerforming = isPerforming,
                                    onRetry = { onRetryEntry(entry.id) },
                                    onCancel = { cancelTarget = entry.id },
                                    phoneLayout = phoneLayout,
                                )
                            }
                            if (queueEntries.size > 20) {
                                Text(
                                    text = "... and ${queueEntries.size - 20} more entries",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 8.dp),
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
 * Status chip showing a count; only an [TseCountTone.Alert] count is coloured.
 *
 * @param label The status label
 * @param count The count value
 * @param tone Neutral (plain text) or Alert (error colour)
 * @param modifier Layout modifier; the rows give each chip an equal weight
 */
@Composable
fun TseQueueStatusChip(
    label: String,
    count: Long,
    tone: TseCountTone,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(4.dp),
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleMedium,
            color =
                when (tone) {
                    TseCountTone.Neutral -> MaterialTheme.colorScheme.onSurface
                    TseCountTone.Alert -> MaterialTheme.colorScheme.error
                },
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Single queue entry item with details and action buttons.
 *
 * @param language Current language for localization
 * @param entry The queue entry data
 * @param isPerforming Whether an operation is in progress
 * @param onRetry Callback to retry this entry
 * @param onCancel Callback to cancel this entry
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TseQueueEntryItem(
    language: Language,
    entry: TseSigningQueueDto.QueueEntryResponseDto,
    isPerforming: Boolean,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    phoneLayout: Boolean = false,
) {
    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Tonal,
        status =
            when (entry.signingStatus) {
                TseSigningStatus.FAILED -> PosCardStatus.Error
                TseSigningStatus.QUEUED -> PosCardStatus.Pending
                TseSigningStatus.PENDING -> PosCardStatus.Pending
                TseSigningStatus.PROCESSING -> PosCardStatus.Active
                TseSigningStatus.SIGNED -> PosCardStatus.Completed
                else -> null
            },
    ) {
        body {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = if (phoneLayout) Modifier.weight(1f) else Modifier,
                    ) {
                        Text(
                            text = "#${entry.id}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = entry.entityType.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TseStatusBadge(entry.signingStatus)
                    }
                    // Action buttons for retryable entries
                    if (entry.signingStatus.canRetry()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            PosIconButton(
                                icon = Icons.Default.Refresh,
                                contentDescription = str(TseSettingsKeys.retryEntry),
                                onClick = onRetry,
                                enabled = !isPerforming,
                                size = if (phoneLayout) 48.dp else 32.dp,
                                iconSize = 18.dp,
                            )
                            PosIconButton(
                                icon = Icons.Default.Cancel,
                                contentDescription = str(TseSettingsKeys.cancelEntry),
                                onClick = onCancel,
                                enabled = !isPerforming,
                                tint = MaterialTheme.colorScheme.error,
                                size = if (phoneLayout) 48.dp else 32.dp,
                                iconSize = 18.dp,
                            )
                        }
                    }
                }
                // Details row
                val processTypeText: @Composable () -> Unit = {
                    Text(
                        text = entry.processType,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val retryCountText: @Composable () -> Unit = {
                    Text(
                        text = "${str(TseSettingsKeys.retryCount)}: ${entry.retryCount}/${entry.maxRetries}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (phoneLayout) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        processTypeText()
                        retryCountText()
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        processTypeText()
                        retryCountText()
                    }
                }
                // Error if present
                entry.lastError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

/**
 * Status badge with colored background.
 *
 * @param status The signing status
 */
@Composable
fun TseStatusBadge(status: TseSigningStatus) {
    val (backgroundColor, textColor) =
        when (status) {
            TseSigningStatus.PENDING -> LocalPosColors.current.posOccupied to MaterialTheme.colorScheme.surface
            TseSigningStatus.PROCESSING -> LocalPosColors.current.posReserved to MaterialTheme.colorScheme.surface
            TseSigningStatus.QUEUED -> LocalPosColors.current.posBilling to MaterialTheme.colorScheme.surface
            TseSigningStatus.SIGNED -> LocalPosColors.current.posAvailable to MaterialTheme.colorScheme.surface
            TseSigningStatus.FAILED -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.onError
            TseSigningStatus.CANCELLED -> MaterialTheme.colorScheme.outline to MaterialTheme.colorScheme.surface
            TseSigningStatus.DISABLED -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surface
            TseSigningStatus.SIMULATION -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surface
            // A non-fiscal cash book (Tresor) is not signed by design, so this is neutral —
            // never an error colour, which would read as something needing attention.
            // Unreachable in practice: only the fiscal book's entries enter the signing queue.
            TseSigningStatus.NOT_APPLICABLE ->
                MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        }
    Surface(
        color = backgroundColor,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = status.name,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            modifier = Modifier.padding(horizontal = PosSpacing.sm, vertical = 2.dp),
        )
    }
}
