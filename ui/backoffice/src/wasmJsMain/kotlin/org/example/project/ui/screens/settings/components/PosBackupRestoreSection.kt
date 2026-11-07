package org.example.project.ui.screens.settings.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestorePage
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.admin.features.settings.presentation.sections.DirectoryBrowserDialog
import org.example.project.admin.features.settings.presentation.sections.DirectoryBrowserState
import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.domain.enums.backup.BackupCopyStatus
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.BackupKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.DatevSettingsKeys
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.formatEpochMillisShortDateTime
import org.example.project.core.utils.formatFileSize
import org.example.project.core.utils.formatInstantShortDateTime
import org.example.project.core.utils.formatShortDateTime
import org.example.project.features.scheduler.presentation.components.JobStatusRow
import org.example.project.features.scheduler.presentation.components.JobStatusRowPhoneWidth
import org.example.project.features.settings.presentation.state.BackupFile
import org.example.project.features.settings.presentation.state.BackupRestoreState
import org.example.project.ui.components.PosAutoSizeText
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosSegmentedButtonRow
import org.example.project.ui.components.PosTextField
import org.example.project.ui.layout.PaneBreakpoints

/** Below this card width the Restore button goes under the file name instead of squeezing it. */
private val BACKUP_CARD_STACK_BELOW: Dp = 420.dp

@Composable
fun PosBackupRestoreSection(
    language: Language,
    state: BackupRestoreState,
    onDefaultPathChange: (String) -> Unit,
    onSaveConfig: () -> Unit,
    onOpenDirectoryBrowser: () -> Unit,
    onCloseDirectoryBrowser: () -> Unit,
    onNavigateDirectory: (String) -> Unit,
    onNavigateToParent: () -> Unit,
    onSelectDirectory: (String) -> Unit,
    onCreateBackup: () -> Unit,
    onRefreshBackups: () -> Unit,
    onSelectRestoreFile: (String) -> Unit,
    onCancelRestore: () -> Unit,
    onConfirmRestore: () -> Unit,
    onAutoEnabledChange: (Boolean) -> Unit = {},
    onCadenceChange: (BackupRestoreDto.BackupCadence) -> Unit = {},
    onRetentionChange: (Int) -> Unit = {},
    onRunHourChange: (Int) -> Unit = {},
    onMaxUploadBytesChange: (Long) -> Unit = {},
    onLoadJobStatus: () -> Unit = {},
    onRunBackupNow: () -> Unit = {},
    onCopyToPathChange: (String) -> Unit = {},
    onCopyRetentionInputChange: (String) -> Unit = {},
) {
    // VM owns the polling loop (single shared poller for both Backup + DATEV).
    // Trigger one immediate refresh on first composition so a freshly-opened section
    // doesn't wait up to 60s for the next tick.
    LaunchedEffect(Unit) { onLoadJobStatus() }
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Description
        Text(
            text = str(DatevSettingsKeys.backupDescription),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Last backup info card
        LastBackupInfoCard(lastBackupAt = state.lastBackupAt)

        // Backup path configuration
        BackupPathConfig(
            defaultBackupPath = state.defaultBackupPath,
            onDefaultPathChange = onDefaultPathChange,
            onOpenDirectoryBrowser = onOpenDirectoryBrowser,
        )

        // Second copy on a mounted device
        BackupCopyConfig(
            state = state,
            onCopyToPathChange = onCopyToPathChange,
            onCopyRetentionInputChange = onCopyRetentionInputChange,
        )

        // Auto-backup configuration
        AutoBackupConfig(
            autoEnabled = state.autoEnabled,
            cadence = state.cadence,
            retentionCount = state.retentionCount,
            runHour = state.runHour,
            maxUploadBytes = state.maxUploadBytes,
            backupJob = state.backupJobStatus,
            running = state.runningBackupJob,
            onAutoEnabledChange = onAutoEnabledChange,
            onCadenceChange = onCadenceChange,
            onRetentionChange = onRetentionChange,
            onRunHourChange = onRunHourChange,
            onMaxUploadBytesChange = onMaxUploadBytesChange,
            onRunBackupNow = onRunBackupNow,
        )

        // Single Save button persists path + auto-backup fields together.
        PosButton(
            text = str(CommonKeys.save),
            icon = Icons.Default.Save,
            onClick = onSaveConfig,
            enabled = !state.isLoadingConfig && !state.copyPathInvalid && !state.copyRetentionInvalid,
            isLoading = state.isLoadingConfig,
            modifier = Modifier.fillMaxWidth(),
        )

        // Create Backup button
        PosButton(
            text = str(DatevSettingsKeys.createBackup),
            icon = Icons.Default.CloudDownload,
            onClick = onCreateBackup,
            enabled = state.canCreateBackup(),
            isLoading = state.isCreatingBackup,
            modifier = Modifier.fillMaxWidth(),
        )

        // Available backups list
        AvailableBackupsList(
            backups = state.availableBackups,
            isLoadingBackups = state.isLoadingBackups,
            isRestoring = state.isRestoring,
            language = language,
            onRefreshBackups = onRefreshBackups,
            onSelectRestoreFile = onSelectRestoreFile,
        )

        // Restore warning
        RestoreWarningCard()
    }

    // Directory Browser Dialog
    if (state.showDirectoryBrowser) {
        DirectoryBrowserDialog(
            browser =
                DirectoryBrowserState(
                    currentPath = state.currentBrowsePath,
                    parentPath = state.parentBrowsePath,
                    entries =
                        state.directoryEntries.map {
                            BackupRestoreDto.DirectoryEntryDto(it.name, it.path, it.isDirectory, it.isReadable, it.isWritable)
                        },
                    canWrite = state.currentPathWritable,
                ),
            onNavigate = onNavigateDirectory,
            onParent = onNavigateToParent,
            onSelect = onSelectDirectory,
            onDismiss = onCloseDirectoryBrowser,
        )
    }

    // Restore confirmation dialog
    if (state.showRestoreConfirmation) {
        PosDialog(
            onDismiss = onCancelRestore,
            title = str(DatevSettingsKeys.restoreConfirmTitle),
            titleIcon = Icons.Default.Warning,
            kind = PosDialogKind.Confirm,
            primary =
                PosDialogAction(
                    str(DatevSettingsKeys.confirmRestore),
                    onConfirmRestore,
                    enabled = !state.isRestoring,
                    isLoading = state.isRestoring,
                ),
            destructive = true,
        ) {
            Text(
                text = str(DatevSettingsKeys.restoreConfirmMessage),
                style = MaterialTheme.typography.bodyMedium,
            )
            state.selectedRestoreFilePath?.let { filePath ->
                Text(
                    text = "${str(DatevSettingsKeys.selectedFile)}: ${filePath.substringAfterLast("/")}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun LastBackupInfoCard(lastBackupAt: String?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.History,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = str(DatevSettingsKeys.lastBackup),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = lastBackupAt?.formatInstantShortDateTime(LocalAppLanguage.current) ?: str(DatevSettingsKeys.noBackupsFound),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

@Composable
private fun BackupPathConfig(
    defaultBackupPath: String,
    onDefaultPathChange: (String) -> Unit,
    onOpenDirectoryBrowser: () -> Unit,
) {
    PosTextField(
        value = defaultBackupPath,
        readOnly = true,
        onValueChange = onDefaultPathChange,
        label = str(DatevSettingsKeys.defaultBackupPath),
        leadingIcon = Icons.Default.FolderOpen,
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            PosIconButton(
                icon = Icons.Default.FolderOpen,
                contentDescription = str(SettingsKeys.browse),
                onClick = onOpenDirectoryBrowser,
                size = 48.dp,
            )
        },
    )
}

@Composable
private fun BackupCopyConfig(
    state: BackupRestoreState,
    onCopyToPathChange: (String) -> Unit,
    onCopyRetentionInputChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PosTextField(
            value = state.copyToPath,
            onValueChange = onCopyToPathChange,
            label = str(BackupKeys.CopyTo),
            leadingIcon = Icons.Default.FolderOpen,
            isError = state.copyPathInvalid,
            errorMessage = if (state.copyPathInvalid) str(BackupKeys.CopyPathInvalid) else null,
            supportingText = { Text(str(BackupKeys.CopyToHint)) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.copyToPath.isNotBlank()) {
            PosTextField(
                value = state.copyRetentionInput,
                onValueChange = onCopyRetentionInputChange,
                label = str(BackupKeys.CopyRetention),
                isError = state.copyRetentionInvalid,
                modifier = Modifier.fillMaxWidth(),
            )
            BackupCopyStatusLine(state)
        }
    }
}

@Composable
private fun BackupCopyStatusLine(state: BackupRestoreState) {
    val statusKey =
        when (state.lastCopyStatus) {
            BackupCopyStatus.OK -> BackupKeys.CopyStatusOk
            BackupCopyStatus.FAILED -> BackupKeys.CopyStatusFailed
            BackupCopyStatus.SKIPPED_NOT_MOUNTED -> BackupKeys.CopyStatusNotMounted
            BackupCopyStatus.SKIPPED_NO_TARGET -> BackupKeys.CopyStatusNoTarget
            null -> null
        }
    val whenText = state.lastCopyAt?.formatInstantShortDateTime(LocalAppLanguage.current) ?: str(BackupKeys.CopyNever)
    // The server's reason is English; the "not mounted" status already says it in the user's language.
    val reason = state.lastCopyError.takeIf { state.lastCopyStatus == BackupCopyStatus.FAILED }
    val detail = listOfNotNull(statusKey?.let { str(it) }, reason).joinToString(" — ")
    Text(
        text = "${str(BackupKeys.CopyLast)}: $whenText" + if (detail.isNotEmpty()) " ($detail)" else "",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.copyWarning) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = str(BackupKeys.CopyWarning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun AvailableBackupsList(
    backups: List<BackupFile>,
    isLoadingBackups: Boolean,
    isRestoring: Boolean,
    language: Language,
    onRefreshBackups: () -> Unit,
    onSelectRestoreFile: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Storage,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = str(DatevSettingsKeys.availableBackups),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                }
                // raw Material 3 by design: IconButton with conditional loading indicator, PosIconButton lacks isLoading
                androidx.compose.material3.IconButton(
                    onClick = onRefreshBackups,
                    enabled = !isLoadingBackups,
                ) {
                    if (isLoadingBackups) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = getString(CommonKeys.refresh, language),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            if (isLoadingBackups) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                }
            } else if (backups.isEmpty()) {
                Text(
                    text = str(DatevSettingsKeys.noBackupsFound),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth < PaneBreakpoints.PHONE_MAX) {
                        // Phone: a plain column. The page is the only scroller; a capped LazyColumn
                        // inside it would capture the touch drag.
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            backups.forEach { backup ->
                                BackupFileCard(
                                    backup = backup,
                                    isRestoring = isRestoring,
                                    onClick = { onSelectRestoreFile(backup.filePath) },
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 240.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(backups) { backup ->
                                BackupFileCard(
                                    backup = backup,
                                    isRestoring = isRestoring,
                                    onClick = { onSelectRestoreFile(backup.filePath) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackupFileCard(
    backup: BackupFile,
    isRestoring: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val details: @Composable (Modifier) -> Unit = { m ->
                Column(modifier = m) {
                    PosAutoSizeText(
                        text = backup.filename,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "${formatFileSize(backup.sizeBytes)} — ${backup.createdAt.formatShortDateTime(LocalAppLanguage.current)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val restore: @Composable (Modifier) -> Unit = { m ->
                PosSecondaryButton(
                    text = str(DatevSettingsKeys.restore),
                    icon = Icons.Default.RestorePage,
                    onClick = onClick,
                    enabled = !isRestoring,
                    modifier = m,
                )
            }
            if (maxWidth < BACKUP_CARD_STACK_BELOW) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    details(Modifier.fillMaxWidth())
                    restore(Modifier.fillMaxWidth())
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    details(Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    restore(Modifier)
                }
            }
        }
    }
}

@Composable
private fun RestoreWarningCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = str(DatevSettingsKeys.restoreWarning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun AutoBackupConfig(
    autoEnabled: Boolean,
    cadence: BackupRestoreDto.BackupCadence,
    retentionCount: Int,
    runHour: Int,
    maxUploadBytes: Long,
    backupJob: org.example.project.core.domain.dto.scheduler.SchedulerDto.ScheduledJobResponseDto?,
    running: Boolean,
    onAutoEnabledChange: (Boolean) -> Unit,
    onCadenceChange: (BackupRestoreDto.BackupCadence) -> Unit,
    onRetentionChange: (Int) -> Unit,
    onRunHourChange: (Int) -> Unit,
    onMaxUploadBytesChange: (Long) -> Unit,
    onRunBackupNow: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = str(BackupKeys.AutoBackupSection),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = str(BackupKeys.AutoEnabled),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Switch(
                        checked = autoEnabled,
                        onCheckedChange = onAutoEnabledChange,
                    )
                }
            }

            // Cadence chips
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = str(BackupKeys.Cadence),
                    style = MaterialTheme.typography.labelMedium,
                )
                PosSegmentedButtonRow(
                    options = listOf(BackupRestoreDto.BackupCadence.DAILY, BackupRestoreDto.BackupCadence.WEEKLY),
                    selectedOption = cadence,
                    onOptionSelected = onCadenceChange,
                ) { option ->
                    str(
                        if (option == BackupRestoreDto.BackupCadence.DAILY) BackupKeys.CadenceDaily else BackupKeys.CadenceWeekly,
                    )
                }
            }

            // Numeric inputs row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PosTextField(
                    value = retentionCount.toString(),
                    onValueChange = { v -> v.toIntOrNull()?.let(onRetentionChange) },
                    label = str(BackupKeys.Retention),
                    modifier = Modifier.weight(1f),
                )
                PosTextField(
                    value = runHour.toString(),
                    onValueChange = { v -> v.toIntOrNull()?.let(onRunHourChange) },
                    label = str(BackupKeys.RunHour),
                    modifier = Modifier.weight(1f),
                )
            }

            // Max upload size input
            PosTextField(
                value = formatFileSize(maxUploadBytes),
                onValueChange = { v ->
                    val bytes = parseFileSize(v)
                    if (bytes != null) onMaxUploadBytesChange(bytes)
                },
                label = str(BackupKeys.MaxUploadSize),
                modifier = Modifier.fillMaxWidth(),
            )

            // Job status row
            JobStatusRow(
                enabled = backupJob?.enabled ?: autoEnabled,
                lastRunAt = formatEpochMillisOrNull(backupJob?.lastRunAt),
                nextRunAt = formatEpochMillisOrNull(backupJob?.nextRunAt),
                lastStatus = backupJob?.lastStatus,
                onRunNow = if (backupJob != null) onRunBackupNow else null,
                running = running,
                stackBelow = JobStatusRowPhoneWidth,
            )
        }
    }
}

@Composable
private fun formatEpochMillisOrNull(epochMillis: Long?): String? {
    if (epochMillis == null || epochMillis == 0L) return null
    return epochMillis.formatEpochMillisShortDateTime(LocalAppLanguage.current)
}

private fun parseFileSize(input: String): Long? {
    val trimmed = input.trim()
    val parts = trimmed.split(" ", limit = 2)
    if (parts.isEmpty()) return null

    val number = parts[0].toLongOrNull() ?: return null
    val unit = parts.getOrNull(1)?.uppercase() ?: return null

    return when (unit) {
        "B" -> number
        "KB" -> number * 1024
        "MB" -> number * 1024 * 1024
        "GB" -> number * 1024 * 1024 * 1024
        else -> null
    }
}
