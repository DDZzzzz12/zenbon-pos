package org.example.project.ui.screens.settings.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import org.example.project.core.domain.dto.tse.TseVerificationDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.DatevSettingsKeys
import org.example.project.core.presentation.strings.keys.TseArchiveKeys
import org.example.project.core.presentation.strings.keys.TseVerificationKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.isCompact
import org.example.project.features.settings.presentation.state.TseExportState
import org.example.project.features.settings.presentation.state.TseExportType
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosDangerButton
import org.example.project.ui.components.PosDangerOutlinedButton
import org.example.project.ui.components.PosDateField
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosStatusChip
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.PosTextField
import org.example.project.ui.theme.PosSpacing
import kotlin.math.roundToLong

/**
 * TSE export section using POS design language.
 *
 * Shows export type selector, date range picker for time-filtered exports,
 * size estimation, export action, and destructive delete with confirmation.
 */
@Composable
fun PosTseExportSection(
    language: Language,
    windowSize: WindowSizeClass,
    state: TseExportState,
    onToggleExpand: () -> Unit,
    onExportTypeChange: (TseExportType) -> Unit,
    onDateRangeChange: (startDate: String, endDate: String) -> Unit,
    onCheckSize: () -> Unit,
    onExport: () -> Unit,
    onRequestDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    isVerifyingExport: Boolean = false,
    verificationReport: TseVerificationDto.ReportDto? = null,
    onVerifyExport: (String) -> Unit = {},
    onClientChange: (String?) -> Unit = {},
    onArchiveFileNameChange: (String) -> Unit = {},
    onRegisterArchive: () -> Unit = {},
) {
    val isCompact = windowSize.isCompact()
    val colorScheme = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Section header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PosSpacing.md),
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = str(DatevSettingsKeys.tseExportTitle),
                        style = MaterialTheme.typography.titleMedium,
                        color = colorScheme.onSurface,
                    )
                    Text(
                        text = str(DatevSettingsKeys.tseExportDescription),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Export type selector
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = state.selectedExportType == TseExportType.FULL,
                    onClick = { onExportTypeChange(TseExportType.FULL) },
                    label = { Text(str(DatevSettingsKeys.tseExportTypeFull)) },
                )
                FilterChip(
                    selected = state.selectedExportType == TseExportType.INCREMENTAL,
                    onClick = { onExportTypeChange(TseExportType.INCREMENTAL) },
                    label = { Text(str(DatevSettingsKeys.tseExportTypeIncremental)) },
                )
                FilterChip(
                    selected = state.selectedExportType == TseExportType.TIME_FILTERED,
                    onClick = { onExportTypeChange(TseExportType.TIME_FILTERED) },
                    label = { Text(str(DatevSettingsKeys.tseExportTypeTimeFiltered)) },
                )
            }

            // Date range picker (only for TIME_FILTERED)
            if (state.selectedExportType == TseExportType.TIME_FILTERED) {
                // The one date field: typed or picked, shown in the UI language, ISO in the state.
                if (isCompact) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PosDateField(
                            value = state.startDate,
                            onValueChange = { value -> onDateRangeChange(value, state.endDate) },
                            label = str(DatevSettingsKeys.startDate),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        PosDateField(
                            value = state.endDate,
                            onValueChange = { value -> onDateRangeChange(state.startDate, value) },
                            label = str(DatevSettingsKeys.endDate),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PosDateField(
                            value = state.startDate,
                            onValueChange = { value -> onDateRangeChange(value, state.endDate) },
                            label = str(DatevSettingsKeys.startDate),
                            modifier = Modifier.weight(1f),
                        )
                        PosDateField(
                            value = state.endDate,
                            onValueChange = { value -> onDateRangeChange(state.startDate, value) },
                            label = str(DatevSettingsKeys.endDate),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                TseExportClientSelector(
                    state = state,
                    onClientChange = onClientChange,
                )
            }

            // Check size button
            // raw Material 3 by design: OutlinedButton with conditional loading icon, PosSecondaryButton lacks isLoading
            androidx.compose.material3.OutlinedButton(
                onClick = onCheckSize,
                enabled = !state.isOperationInProgress(),
            ) {
                if (state.isLoadingSize) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(str(DatevSettingsKeys.tseCheckExportSize))
            }

            // Export size display
            state.exportSize?.let { exportSize ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        TseExportSizeRow(
                            label = str(DatevSettingsKeys.tseFullExportSize),
                            value = formatBytesReadable(exportSize.fullExportSizeBytes),
                            isCompact = isCompact,
                        )
                        Spacer(Modifier.height(4.dp))
                        TseExportSizeRow(
                            label = str(DatevSettingsKeys.tseIncrementalExportSize),
                            value = formatBytesReadable(exportSize.incrementalExportSizeBytes),
                            isCompact = isCompact,
                        )
                    }
                }
            }

            // Export TAR button
            PosButton(
                text = if (state.isExporting) str(DatevSettingsKeys.tseExporting) else str(DatevSettingsKeys.tseExportTar),
                icon = Icons.Default.Download,
                onClick = onExport,
                enabled = state.canExport(),
                isLoading = state.isExporting,
                modifier = Modifier.fillMaxWidth(),
            )

            // Export result
            val result = state.exportResult
            if (result != null && result.success) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = str(DatevSettingsKeys.tseExportComplete),
                            style = MaterialTheme.typography.titleSmall,
                            color = colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.height(8.dp))
                        val filePath = result.filePath
                        if (filePath != null) {
                            TseExportResultRow(
                                label = str(DatevSettingsKeys.tseExportFilePath),
                                value = filePath,
                            )
                        }
                        TseExportResultRow(
                            label = str(DatevSettingsKeys.tseExportFileSize),
                            value = formatBytesReadable(result.fileSize),
                        )
                        TseExportResultRow(
                            label = str(DatevSettingsKeys.tseExportDuration),
                            value = "${result.exportDurationMs}ms",
                        )
                    }
                }
            }

            // Divider before export verification (AGD §5.2 log-sequence plausibility)
            HorizontalDivider(color = colorScheme.outlineVariant)

            // Verify export — always the most recently exported file (from the export result
            // above). Disabled without one; the server re-validates the name regardless (rule 8).
            val lastExportFileName = state.exportResult?.fileName
            PosSecondaryButton(
                text = if (isVerifyingExport) str(TseVerificationKeys.verifying) else str(TseVerificationKeys.verifyExport),
                icon = Icons.AutoMirrored.Filled.FactCheck,
                onClick = { lastExportFileName?.let(onVerifyExport) },
                enabled = lastExportFileName != null && !isVerifyingExport,
                isLoading = isVerifyingExport,
                modifier = Modifier.fillMaxWidth(),
            )
            if (lastExportFileName == null) {
                Text(
                    text = str(TseVerificationKeys.noExportSelected),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }

            if (verificationReport != null) {
                TseVerificationReportCard(verificationReport)
            }

            // Divider before delete section
            HorizontalDivider(color = colorScheme.outlineVariant)

            // Delete warning
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = str(DatevSettingsKeys.tseDeleteWarning),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }

            // Delete button (destructive)
            PosDangerOutlinedButton(
                text = if (state.isDeleting) str(DatevSettingsKeys.tseDeleting) else str(DatevSettingsKeys.tseDeleteExportedData),
                icon = Icons.Default.Delete,
                onClick = onRequestDelete,
                enabled = !state.isOperationInProgress(),
                isLoading = state.isDeleting,
            )

            HorizontalDivider(color = colorScheme.outlineVariant)

            TseArchiveRegistration(
                state = state,
                onArchiveFileNameChange = onArchiveFileNameChange,
                onRegisterArchive = onRegisterArchive,
            )
        }
    }

    // Delete confirmation dialog using PosDialog
    if (state.showDeleteConfirmation) {
        PosDialog(
            onDismiss = onCancelDelete,
            title = str(DatevSettingsKeys.tseDeleteConfirmTitle),
            titleIcon = Icons.Default.Warning,
            showCloseButton = true,
            footer = {
                PosTextButton(
                    text = getString(CommonKeys.cancel, language),
                    onClick = onCancelDelete,
                )
                Spacer(Modifier.weight(1f))
                PosDangerButton(
                    // Was confirmRestore -- "Yes, Restore" on a delete dialog.
                    text = str(DatevSettingsKeys.tseDeleteConfirmButton),
                    onClick = onConfirmDelete,
                )
            },
        ) {
            Text(
                text = str(DatevSettingsKeys.tseDeleteConfirmMessage),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * "Archiv registrieren": enters a TAR already in the server's TSE export folder in the archive
 * register, so a DSFinV-K export can cover a period erased from the TSE (real-TSE QA rt14). The
 * server validates the name, the file and duplicates; the button only needs a name (rule 8).
 */
@Composable
private fun TseArchiveRegistration(
    state: TseExportState,
    onArchiveFileNameChange: (String) -> Unit,
    onRegisterArchive: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.unregisteredArchiveFileName?.let { fileName ->
            // The TSE is already erased: until this archive is registered, a DSFinV-K export of the
            // deleted period cannot carry its TSE data.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = colorScheme.error, modifier = Modifier.size(18.dp))
                Text(
                    text = str(TseArchiveKeys.notRegisteredAfterDelete, fileName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(
            text = str(TseArchiveKeys.registerTitle),
            style = MaterialTheme.typography.titleSmall,
            color = colorScheme.onSurface,
        )
        Text(
            text = str(TseArchiveKeys.registerDescription),
            style = MaterialTheme.typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
        )
        PosTextField(
            value = state.archiveFileName,
            onValueChange = onArchiveFileNameChange,
            label = str(TseArchiveKeys.fileNameLabel),
            enabled = !state.isOperationInProgress(),
            modifier = Modifier.fillMaxWidth(),
        )
        PosSecondaryButton(
            text = if (state.isRegisteringArchive) str(TseArchiveKeys.registering) else str(TseArchiveKeys.registerButton),
            icon = Icons.Default.Archive,
            onClick = onRegisterArchive,
            enabled = state.canRegisterArchive(),
            isLoading = state.isRegisteringArchive,
            modifier = Modifier.fillMaxWidth(),
        )
        state.registeredArchive?.let { archive ->
            Text(
                text =
                    str(
                        TseArchiveKeys.registered,
                        archive.fileName,
                        archive.tseSerialHex.take(12),
                        archive.tanrFrom?.toString() ?: "–",
                        archive.tanrTo?.toString() ?: "–",
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.primary,
            )
        }
    }
}

/** "Kasse": all registers (default) or one register's TSE client, as chips like the type selector. */
@Composable
private fun TseExportClientSelector(
    state: TseExportState,
    onClientChange: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = str(DatevSettingsKeys.tseExportClientLabel),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(
                selected = state.selectedClientId == null,
                onClick = { onClientChange(null) },
                label = { Text(str(DatevSettingsKeys.tseExportAllClients)) },
            )
            state.clientOptions.forEach { option ->
                FilterChip(
                    selected = state.selectedClientId == option.clientId,
                    onClick = { onClientChange(option.clientId) },
                    label = { Text(option.displayName) },
                )
            }
        }
    }
}

@Composable
private fun TseExportSizeRow(
    label: String,
    value: String,
    isCompact: Boolean,
) {
    if (isCompact) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun TseExportResultRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/**
 * Renders a [TseVerificationDto.ReportDto]: a verdict chip, the record/transaction summary,
 * and one row per finding (severity + message).
 */
@Composable
private fun TseVerificationReportCard(report: TseVerificationDto.ReportDto) {
    val colorScheme = MaterialTheme.colorScheme
    val backgroundColor =
        when (report.verdict) {
            TseVerificationDto.Verdict.FAIL -> colorScheme.errorContainer
            TseVerificationDto.Verdict.PASS_WITH_WARNINGS -> colorScheme.tertiaryContainer
            TseVerificationDto.Verdict.PASS -> colorScheme.primaryContainer
        }
    val contentColor =
        when (report.verdict) {
            TseVerificationDto.Verdict.FAIL -> colorScheme.onErrorContainer
            TseVerificationDto.Verdict.PASS_WITH_WARNINGS -> colorScheme.onTertiaryContainer
            TseVerificationDto.Verdict.PASS -> colorScheme.onPrimaryContainer
        }
    val verdictIcon =
        when (report.verdict) {
            TseVerificationDto.Verdict.FAIL -> Icons.Default.Error
            TseVerificationDto.Verdict.PASS_WITH_WARNINGS -> Icons.Default.Warning
            TseVerificationDto.Verdict.PASS -> Icons.Default.CheckCircle
        }
    val verdictText =
        when (report.verdict) {
            TseVerificationDto.Verdict.FAIL -> str(TseVerificationKeys.verdictFail)
            TseVerificationDto.Verdict.PASS_WITH_WARNINGS -> str(TseVerificationKeys.verdictWarnings)
            TseVerificationDto.Verdict.PASS -> str(TseVerificationKeys.verdictPass)
        }

    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Plain,
    ) {
        body {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PosStatusChip(
                    text = verdictText,
                    backgroundColor = backgroundColor,
                    textColor = contentColor,
                    icon = verdictIcon,
                )
                Text(
                    text = str(TseVerificationKeys.summary, report.recordCount, report.transactionCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurface,
                )
                if (report.findings.isNotEmpty()) {
                    Text(
                        text = str(TseVerificationKeys.findingsTitle),
                        style = MaterialTheme.typography.titleSmall,
                        color = colorScheme.onSurface,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(PosSpacing.sm)) {
                        report.findings.forEach { finding ->
                            TseFindingRow(finding)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TseFindingRow(finding: TseVerificationDto.FindingDto) {
    val colorScheme = MaterialTheme.colorScheme
    val (icon, tint) =
        when (finding.severity) {
            TseVerificationDto.Severity.CRITICAL -> Icons.Default.Error to colorScheme.error
            TseVerificationDto.Severity.WARNING -> Icons.Default.Warning to colorScheme.tertiary
            TseVerificationDto.Severity.INFO -> Icons.Default.Info to colorScheme.onSurfaceVariant
        }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = finding.message,
            style = MaterialTheme.typography.bodySmall,
            color = colorScheme.onSurface,
        )
    }
}

private fun formatBytesReadable(bytes: Long): String =
    when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${formatDecimal(bytes.toDouble() / (1024 * 1024), 1)} MB"
        else -> "${formatDecimal(bytes.toDouble() / (1024 * 1024 * 1024), 2)} GB"
    }

/**
 * Multiplatform-safe replacement for `"%.<decimals>f".format(value)`: `String.format` is
 * JVM-only and is an unresolved reference on wasmJs. Assumes a non-negative [value], which
 * holds for the byte-size ratios this is used with.
 */
private fun formatDecimal(
    value: Double,
    decimals: Int,
): String {
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val scaled = (value * factor).roundToLong()
    val whole = scaled / factor
    val fraction = (scaled % factor).toString().padStart(decimals, '0')
    return "$whole.$fraction"
}
