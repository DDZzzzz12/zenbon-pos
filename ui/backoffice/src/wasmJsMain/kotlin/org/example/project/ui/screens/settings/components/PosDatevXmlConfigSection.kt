package org.example.project.ui.screens.settings.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.example.project.admin.features.settings.presentation.sections.DirectoryBrowserDialog
import org.example.project.admin.features.settings.presentation.sections.DirectoryBrowserState
import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.domain.enums.setting.DatevAutoCadence
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.BackupKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.DatevKeys
import org.example.project.core.presentation.strings.keys.DatevSettingsKeys
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.formatEpochMillisShortDateTime
import org.example.project.features.scheduler.presentation.components.JobStatusRow
import org.example.project.features.scheduler.presentation.components.JobStatusRowPhoneWidth
import org.example.project.features.settings.presentation.state.DatevXmlConfigState
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosSegmentedButtonRow
import org.example.project.ui.components.PosTextField

@Composable
fun PosDatevXmlConfigSection(
    language: Language,
    state: DatevXmlConfigState,
    onKassenNameChange: (String) -> Unit,
    onExportPathChange: (String) -> Unit,
    onConsultantNumberChange: (String) -> Unit,
    onClientNumberChange: (String) -> Unit,
    onSammeldebitorChange: (String) -> Unit,
    onApExportModeChange: (String) -> Unit,
    onAutoEnabledChange: (Boolean) -> Unit,
    onKassenbuchCadenceChange: (DatevAutoCadence) -> Unit,
    onApLedgerCadenceChange: (DatevAutoCadence) -> Unit,
    onBuchungsstapelCadenceChange: (DatevAutoCadence) -> Unit,
    onRunHourChange: (Int) -> Unit,
    onLoadDatevJobStatus: () -> Unit,
    onRunDatevNow: () -> Unit,
    onOpenDirectoryBrowser: () -> Unit,
    onCloseDirectoryBrowser: () -> Unit,
    onNavigateDirectory: (String) -> Unit,
    onNavigateToParent: () -> Unit,
    onSelectDirectory: (String) -> Unit,
    onSave: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = str(DatevSettingsKeys.datevXmlConfigTitle),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }

            // Description
            Text(
                text = str(DatevSettingsKeys.datevXmlConfigDescription),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp).padding(8.dp),
                )
            } else {
                // Kassen-Name
                PosTextField(
                    value = state.kassenName,
                    onValueChange = onKassenNameChange,
                    label = str(DatevSettingsKeys.kassenName),
                    modifier = Modifier.fillMaxWidth(),
                )

                // Export path with browse button
                PosTextField(
                    value = state.exportPath,
                    onValueChange = onExportPathChange,
                    label = str(DatevSettingsKeys.exportPath),
                    modifier = Modifier.fillMaxWidth(),
                    imeAction = ImeAction.Done,
                    trailingIcon = {
                        PosIconButton(
                            icon = Icons.Default.FolderOpen,
                            contentDescription = str(SettingsKeys.browse),
                            onClick = onOpenDirectoryBrowser,
                            size = 48.dp,
                        )
                    },
                )

                // Beraternummer (Consultant Number)
                PosTextField(
                    value = state.consultantNumber,
                    onValueChange = onConsultantNumberChange,
                    label = str(DatevSettingsKeys.consultantNumber),
                    modifier = Modifier.fillMaxWidth(),
                )

                // Mandantennummer (Client Number)
                PosTextField(
                    value = state.clientNumber,
                    onValueChange = onClientNumberChange,
                    label = str(DatevSettingsKeys.clientNumber),
                    modifier = Modifier.fillMaxWidth(),
                )

                // Sammeldebitor (Personenkonto) — used by the Ausgangsrechnungen (DATEV Debitoren) export.
                PosTextField(
                    value = state.sammeldebitor,
                    onValueChange = onSammeldebitorChange,
                    label = str(SettingsKeys.datevArSammeldebitor),
                    modifier = Modifier.fillMaxWidth(),
                    imeAction = ImeAction.Done,
                )

                // AP Export Mode
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = str(DatevSettingsKeys.apExportMode),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    PosSegmentedButtonRow(
                        options = listOf("MINIMAL", "BEST_EFFORT"),
                        selectedOption = state.apExportMode,
                        onOptionSelected = onApExportModeChange,
                    ) { mode ->
                        str(
                            if (mode == "MINIMAL") {
                                DatevSettingsKeys.apExportModeMinimalShort
                            } else {
                                DatevSettingsKeys.apExportModeBestEffortShort
                            },
                        )
                    }
                    Text(
                        text = str(DatevSettingsKeys.apExportModeDescription),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider()

                PosDatevAutoExportBlock(
                    state = state,
                    onAutoEnabledChange = onAutoEnabledChange,
                    onKassenbuchCadenceChange = onKassenbuchCadenceChange,
                    onApLedgerCadenceChange = onApLedgerCadenceChange,
                    onBuchungsstapelCadenceChange = onBuchungsstapelCadenceChange,
                    onRunHourChange = onRunHourChange,
                    onLoadJobStatus = onLoadDatevJobStatus,
                    onRunNow = onRunDatevNow,
                )

                // Save button
                PosButton(
                    text = getString(CommonKeys.save, language),
                    icon = Icons.Default.Save,
                    onClick = onSave,
                    enabled = !state.isSaving && state.kassenName.isNotBlank(),
                    isLoading = state.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    // Directory browser dialog
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
}

@Composable
private fun PosDatevAutoExportBlock(
    state: DatevXmlConfigState,
    onAutoEnabledChange: (Boolean) -> Unit,
    onKassenbuchCadenceChange: (DatevAutoCadence) -> Unit,
    onApLedgerCadenceChange: (DatevAutoCadence) -> Unit,
    onBuchungsstapelCadenceChange: (DatevAutoCadence) -> Unit,
    onRunHourChange: (Int) -> Unit,
    onLoadJobStatus: () -> Unit,
    onRunNow: () -> Unit,
) {
    // VM owns the polling loop; this just triggers an immediate refresh on mount.
    LaunchedEffect(Unit) { onLoadJobStatus() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = str(DatevKeys.AutoExportSection),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = str(DatevKeys.AutoExportEnabled),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(checked = state.enabled, onCheckedChange = onAutoEnabledChange)
        }

        if (datevAutoExportHasNoCadence(
                state.enabled,
                state.kassenbuchCadence,
                state.apLedgerCadence,
                state.buchungsstapelCadence,
            )
        ) {
            Text(
                text = str(DatevKeys.NoCadenceHint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        PosDatevCadenceRow(
            label = str(DatevKeys.KassenbuchCadence),
            selected = state.kassenbuchCadence,
            onChange = onKassenbuchCadenceChange,
        )
        PosDatevCadenceRow(
            label = str(DatevKeys.ApLedgerCadence),
            selected = state.apLedgerCadence,
            onChange = onApLedgerCadenceChange,
        )
        PosDatevCadenceRow(
            label = str(DatevKeys.BuchungsstapelCadence),
            selected = state.buchungsstapelCadence,
            onChange = onBuchungsstapelCadenceChange,
        )

        PosTextField(
            value = state.runHour.toString(),
            onValueChange = { v -> v.toIntOrNull()?.let(onRunHourChange) },
            label = str(BackupKeys.RunHour),
            modifier = Modifier.fillMaxWidth(),
        )

        JobStatusRow(
            enabled = state.datevJobStatus?.enabled ?: false,
            lastRunAt = formatEpochMillisOrNull(state.datevJobStatus?.lastRunAt),
            nextRunAt = formatEpochMillisOrNull(state.datevJobStatus?.nextRunAt),
            lastStatus = state.datevJobStatus?.lastStatus,
            onRunNow = if (state.datevJobStatus != null) onRunNow else null,
            running = state.runningDatevJob,
            stackBelow = JobStatusRowPhoneWidth,
        )
    }
}

@Composable
private fun PosDatevCadenceRow(
    label: String,
    selected: DatevAutoCadence,
    onChange: (DatevAutoCadence) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        PosSegmentedButtonRow(
            options = DatevAutoCadence.entries,
            selectedOption = selected,
            onOptionSelected = onChange,
        ) { cadence ->
            str(
                when (cadence) {
                    DatevAutoCadence.OFF -> DatevKeys.CadenceOff
                    DatevAutoCadence.WEEKLY -> DatevKeys.CadenceWeekly
                    DatevAutoCadence.MONTHLY -> DatevKeys.CadenceMonthly
                },
            )
        }
    }
}

@Composable
private fun formatEpochMillisOrNull(epochMillis: Long?): String? {
    if (epochMillis == null || epochMillis == 0L) return null
    return epochMillis.formatEpochMillisShortDateTime(LocalAppLanguage.current)
}
