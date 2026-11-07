package org.example.project.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.components.notification.DisplayMode
import org.example.project.core.presentation.components.notification.GlobalOverlayViewModel
import org.example.project.core.presentation.components.notification.LocalGlobalOverlayViewModel
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.DatevSettingsKeys
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.settings.presentation.components.datev.DatevAccountMappingSection
import org.example.project.features.settings.presentation.components.datev.DatevAutoAssignCallbacks
import org.example.project.features.settings.presentation.state.DatevSettingsState
import org.example.project.features.settings.presentation.viewmodel.DatevSettingsViewModel
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosSuccessWatcher
import org.example.project.ui.guard.rememberDirtySource
import org.example.project.ui.layout.PaneSection
import org.example.project.ui.screens.settings.components.PosBackupRestoreSection
import org.example.project.ui.screens.settings.components.PosDatevXmlConfigSection
import org.example.project.ui.screens.settings.components.SettingsContentHeader
import org.example.project.ui.theme.LocalPosLayout
import org.koin.compose.viewmodel.koinViewModel

/**
 * @param outputSections extra pane sections placed after the account mapping and before the backup,
 *   inside this screen's scrolling column: this content owns the only `verticalScroll` on the
 *   destination and fills the viewport, so anything laid out beside it lands past the bottom of a
 *   non-scrolling parent and can never be reached. The caller has already dropped the sections whose
 *   capability the principal lacks.
 * @param canManageBackups whether the principal holds `BACKUP_CREATE` — what `BackupRules` requires
 *   of every backup read. The ViewModel withholds the same calls (see `DatevSettingsViewModel`).
 *
 * The panels are peers, so they sit in an [ExportSectionHost]: one at a time behind a wrapping chip
 * selector at every width (hidden panels stay composed, so unsaved input and the mapping's one-time
 * load survive a switch). The chip is each panel's heading; the host adds no scroll.
 */
@Composable
fun PosExportContent(
    language: Language,
    isCompact: Boolean,
    canManageBackups: Boolean,
    outputSections: List<PaneSection> = emptyList(),
    viewModel: DatevSettingsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val overlay = LocalGlobalOverlayViewModel.current

    ExportLoadingEffects(language, uiState, overlay, viewModel)

    val sections =
        buildList {
            add(PaneSection(PANE_DATEV_CONFIG, str(DatevSettingsKeys.datevXmlConfigTitle)) { DatevConfigPane(language, uiState, viewModel) })
            add(PaneSection(PANE_ACCOUNT_MAPPING, str(DatevSettingsKeys.accountMappingTitle)) { AccountMappingPane(uiState, viewModel) })
            addAll(outputSections)
            // Backup & Restore — BACKUP_CREATE only (BackupRules gates every /api/v1/backup read on it)
            if (canManageBackups) {
                add(PaneSection(PANE_BACKUP, str(DatevSettingsKeys.backupTitle)) { BackupPane(language, uiState, viewModel) })
            }
        }

    Column(modifier = Modifier.fillMaxSize()) {
        if (!isCompact) {
            SettingsContentHeader(
                icon = Icons.Default.Download,
                title = str(SettingsKeys.dataExportTab),
            )
        }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(LocalPosLayout.current.screenPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ExportSectionHost(sections = sections)

            Spacer(Modifier.height(32.dp))
        }
    }
}

private const val PANE_DATEV_CONFIG = "datev-config"
private const val PANE_ACCOUNT_MAPPING = "account-mapping"
private const val PANE_BACKUP = "backup"

/** Ids of the gated output panes built in DataExportSection; internal so both files name one constant. */
internal const val PANE_DATEV_OUTPUT = "datev-output"
internal const val PANE_DSFINVK = "dsfinvk"

@Composable
private fun DatevConfigPane(
    language: Language,
    uiState: DatevSettingsState,
    viewModel: DatevSettingsViewModel,
) {
    // Inert-guard safe; Discard restores the last loaded or saved config locally.
    rememberDirtySource(dirty = uiState.datevXmlConfigState.isDirty, onDiscard = viewModel::discardDatevXmlConfigEdits)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PosDatevXmlConfigSection(
            language = language,
            state = uiState.datevXmlConfigState,
            onKassenNameChange = { viewModel.updateDatevXmlKassenName(it) },
            onExportPathChange = { viewModel.updateDatevXmlExportPath(it) },
            onConsultantNumberChange = { viewModel.updateDatevXmlConsultantNumber(it) },
            onClientNumberChange = { viewModel.updateDatevXmlClientNumber(it) },
            onSammeldebitorChange = { viewModel.updateDatevXmlSammeldebitor(it) },
            onApExportModeChange = { viewModel.updateDatevApExportMode(it) },
            onAutoEnabledChange = { viewModel.onDatevAutoEnabledChange(it) },
            onKassenbuchCadenceChange = { viewModel.onKassenbuchCadenceChange(it) },
            onApLedgerCadenceChange = { viewModel.onApLedgerCadenceChange(it) },
            onBuchungsstapelCadenceChange = { viewModel.onBuchungsstapelCadenceChange(it) },
            onRunHourChange = { viewModel.onDatevRunHourChange(it) },
            onLoadDatevJobStatus = { viewModel.loadDatevJobStatus() },
            onRunDatevNow = { viewModel.onRunDatevNow() },
            onOpenDirectoryBrowser = { viewModel.openDatevXmlDirectoryBrowser() },
            onCloseDirectoryBrowser = { viewModel.closeDatevXmlDirectoryBrowser() },
            onNavigateDirectory = { viewModel.loadDatevXmlDirectory(it) },
            onNavigateToParent = { viewModel.navigateDatevXmlToParent() },
            onSelectDirectory = { viewModel.selectDatevXmlDirectory(it) },
            onSave = { viewModel.saveDatevXmlConfig() },
        )
    }
}

@Composable
private fun AccountMappingPane(
    uiState: DatevSettingsState,
    viewModel: DatevSettingsViewModel,
) {
    rememberDirtySource(dirty = uiState.accountMappingState.isDirty, onDiscard = viewModel::discardAccountMappingEdits)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DatevAccountMappingSection(
            state = uiState.accountMappingState,
            onKontenrahmenSelected = { viewModel.onAccountMappingKontenrahmenSelected(it) },
            onRequestApplyDefaults = { viewModel.requestApplyAccountMappingDefaults() },
            onConfirmApplyDefaults = { viewModel.applyAccountMappingDefaults() },
            onCancelApplyDefaults = { viewModel.cancelApplyAccountMappingDefaults() },
            onKasseKontoChange = { viewModel.updateAccountMappingKasseKonto(it) },
            onCellChange = { isIncome, kind, rate, konto, bu ->
                viewModel.updateAccountMappingCell(isIncome, kind, rate, konto, bu)
            },
            onPaymentCellChange = { id, konto, bu -> viewModel.updateAccountMappingPaymentCell(id, konto, bu) },
            onSpecialCellChange = { slot, konto, bu -> viewModel.updateAccountMappingSpecialCell(slot, konto, bu) },
            autoAssign =
                DatevAutoAssignCallbacks(
                    onRateCell = { isIncome, kind, rate, on -> viewModel.setAccountMappingCellAutoAssign(isIncome, kind, rate, on) },
                    onPaymentCell = { id, on -> viewModel.setAccountMappingPaymentAutoAssign(id, on) },
                    onSpecialCell = { slot, on -> viewModel.setAccountMappingSpecialAutoAssign(slot, on) },
                    onSwitchOffAllSpecial = { viewModel.switchOffAllSpecialAutoAssign() },
                ),
            onLoad = { viewModel.loadDatevAccountMapping() },
            onSave = { viewModel.saveDatevAccountMapping() },
        )
    }
}

@Composable
private fun BackupPane(
    language: Language,
    uiState: DatevSettingsState,
    viewModel: DatevSettingsViewModel,
) {
    // Only the backup CONFIG is a draft; create/restore/browse are actions, not unsaved edits.
    rememberDirtySource(dirty = uiState.backupState.isConfigDirty, onDiscard = viewModel::discardBackupConfigEdits)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PosBackupRestoreSection(
            language = language,
            state = uiState.backupState,
            onDefaultPathChange = { viewModel.updateDefaultBackupPath(it) },
            onSaveConfig = { viewModel.saveBackupConfig() },
            onOpenDirectoryBrowser = { viewModel.openDirectoryBrowser() },
            onCloseDirectoryBrowser = { viewModel.closeDirectoryBrowser() },
            onNavigateDirectory = { viewModel.loadDirectory(it) },
            onNavigateToParent = { viewModel.navigateToParent() },
            onSelectDirectory = { viewModel.selectDirectory(it) },
            onCreateBackup = { viewModel.createBackup() },
            onRefreshBackups = { viewModel.loadAvailableBackups() },
            onSelectRestoreFile = { viewModel.selectRestoreFile(it) },
            onCancelRestore = { viewModel.cancelRestore() },
            onConfirmRestore = { viewModel.confirmRestore() },
            onAutoEnabledChange = { viewModel.onAutoEnabledChange(it) },
            onCadenceChange = { viewModel.onCadenceChange(it) },
            onRetentionChange = { viewModel.onRetentionChange(it) },
            onRunHourChange = { viewModel.onRunHourChange(it) },
            onMaxUploadBytesChange = { viewModel.onMaxUploadBytesChange(it) },
            onLoadJobStatus = { viewModel.loadBackupJobStatus() },
            onRunBackupNow = { viewModel.onRunBackupNow() },
            onCopyToPathChange = { viewModel.onCopyToPathChange(it) },
            onCopyRetentionInputChange = { viewModel.onCopyRetentionInputChange(it) },
        )
    }
}

@Composable
private fun ExportLoadingEffects(
    language: Language,
    uiState: DatevSettingsState,
    overlay: GlobalOverlayViewModel,
    viewModel: DatevSettingsViewModel,
) {
    var datevXmlSaveLoadingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(uiState.datevXmlConfigState.isSaving) {
        if (uiState.datevXmlConfigState.isSaving) {
            datevXmlSaveLoadingId =
                overlay.showLoading(
                    title = getString(DatevSettingsKeys.exportSectionTitle, language),
                    message = getString(DatevSettingsKeys.savingDatevConfig, language),
                )
        } else {
            datevXmlSaveLoadingId?.let {
                overlay.dismissById(it)
                datevXmlSaveLoadingId = null
            }
        }
    }

    var backupLoadingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(uiState.backupState.isCreatingBackup) {
        if (uiState.backupState.isCreatingBackup) {
            backupLoadingId =
                overlay.showLoading(
                    title = getString(DatevSettingsKeys.exportSectionTitle, language),
                    message = getString(DatevSettingsKeys.creatingBackup, language),
                )
        } else {
            backupLoadingId?.let {
                overlay.dismissById(it)
                backupLoadingId = null
            }
        }
    }

    var restoreLoadingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(uiState.backupState.isRestoring) {
        if (uiState.backupState.isRestoring) {
            restoreLoadingId =
                overlay.showLoading(
                    title = getString(DatevSettingsKeys.exportSectionTitle, language),
                    message = getString(DatevSettingsKeys.restoringBackup, language),
                )
        } else {
            restoreLoadingId?.let {
                overlay.dismissById(it)
                restoreLoadingId = null
            }
        }
    }

    PosErrorWatcher(
        error = uiState.error,
        title = getString(DatevSettingsKeys.exportError, language),
        onClear = { viewModel.clearError() },
        displayMode = DisplayMode.DIALOG,
        retryAction = { viewModel.clearError() },
    )

    // Backup, restore and Kassenmeldung export each carry their own title and a localized body (F63).
    val notice = uiState.notice
    PosSuccessWatcher(
        message = notice?.let { getString(it.message, language, *it.args.toTypedArray()) },
        title = notice?.let { getString(it.title, language) } ?: "",
        onClear = { viewModel.clearNotice() },
    )

    // A save is not an export: it gets the "saved" title and a localized message (F54 #6).
    PosSuccessWatcher(
        message = uiState.savedMessage?.let { getString(it, language) },
        title = getString(SettingsKeys.saveSuccess, language),
        onClear = { viewModel.clearSavedMessage() },
    )
}
