/**
 * DATEV Settings ViewModel.
 *
 * Manages state and operations for the DATEV settings tab including:
 * - DSFinV-K export (German fiscal compliance ZIP archive)
 * - Kassenbuch CSV export (DATEV-compliant cash book)
 * - Database backup and restore operations
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.settings.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.project.core.data.remote.AccountingConfigurationApiClient
import org.example.project.core.data.remote.BackupApiClient
import org.example.project.core.data.remote.FiscalConfigurationApiClient
import org.example.project.core.data.remote.PaymentTypeApiClient
import org.example.project.core.data.remote.ShopConfigurationApiClient
import org.example.project.core.data.remote.TseSettingsApiClient
import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.domain.dto.datev.DatevAccountMappingDto
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.core.domain.enums.datev.KassenbuchSpecialBooking
import org.example.project.core.domain.enums.datev.Kontenrahmen
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.domain.enums.scheduler.ScheduledJobType
import org.example.project.core.domain.enums.setting.DatevAutoCadence
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.strings.keys.DatevSettingsKeys
import org.example.project.core.utils.FileSaver
import org.example.project.core.utils.datev.DatevAccountMappingDefaults
import org.example.project.features.scheduler.data.remote.ScheduledJobApiClient
import org.example.project.features.settings.presentation.state.BackupFile
import org.example.project.features.settings.presentation.state.DatevAccountMappingCellState
import org.example.project.features.settings.presentation.state.DatevAccountMappingPaymentRow
import org.example.project.features.settings.presentation.state.DatevAccountMappingState
import org.example.project.features.settings.presentation.state.DatevSettingsState
import org.example.project.features.settings.presentation.state.DirectoryEntry
import org.example.project.features.settings.presentation.state.SettingsNotice

/**
 * ViewModel for DATEV settings tab.
 *
 * Handles DSFinV-K export, Kassenbuch export, and backup/restore operations
 * with proper state management and file download handling.
 *
 * @param backupApiClient API client for backup/restore operations
 */
@OptIn(kotlin.time.ExperimentalTime::class)
class DatevSettingsViewModel(
    private val backupApiClient: BackupApiClient,
    private val tseSettingsApi: TseSettingsApiClient,
    private val shopConfigurationApiClient: ShopConfigurationApiClient,
    private val accountingConfigurationApiClient: AccountingConfigurationApiClient,
    private val fiscalConfigurationApiClient: FiscalConfigurationApiClient,
    private val scheduledJobApiClient: ScheduledJobApiClient,
    private val paymentTypeApiClient: PaymentTypeApiClient,
    /**
     * Whether the principal holds `BACKUP_CREATE`, which `BackupRules` puts on every backup read
     * (`/config`, `/list`, `/directories`). Without it the backup calls are never made: init used to
     * load the backup config for everyone, and a manager opening Datenexport was greeted by a 403
     * "BACKUP_CREATE" error dialog for a section that is not even shown to them (QA admin19 NOTE-h).
     */
    private val canManageBackups: () -> Boolean = { true },
) : ViewModel() {
    private val logger = Logger.withTag("DatevSettingsViewModel")

    private val _uiState = MutableStateFlow(DatevSettingsState())
    val uiState: StateFlow<DatevSettingsState> = _uiState.asStateFlow()

    init {
        // Initialize with sensible date defaults (last month)
        loadDatevXmlConfig()
        if (canManageBackups()) loadBackupConfig()
        loadKassenMeldungConfig()
        startScheduledJobPolling()
    }

    /**
     * Single shared poller for /api/scheduled-jobs. Refreshes both backup + DATEV
     * job status every 60s. Replaces the per-section LaunchedEffect loops so the
     * endpoint is hit at most once per cycle.
     */
    private fun startScheduledJobPolling() {
        viewModelScope.launch {
            while (true) {
                refreshScheduledJobStatuses()
                kotlinx.coroutines.delay(60_000L)
            }
        }
    }

    fun loadKassenMeldungConfig() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    kassenMeldungState = it.kassenMeldungState.copy(isLoading = true),
                )
            }
            runCatchingApi { fiscalConfigurationApiClient.getKassenMeldungConfig(1L) }
                .onSuccess { data ->
                    _uiState.update {
                        it.copy(
                            kassenMeldungState =
                                it.kassenMeldungState.copy(
                                    data = data,
                                    isLoading = false,
                                ),
                        )
                    }
                }.onError { _ ->
                    _uiState.update {
                        it.copy(
                            kassenMeldungState = it.kassenMeldungState.copy(isLoading = false),
                        )
                    }
                }
        }
    }

    /**
     * Export Kassenmeldung data as PDF.
     */
    fun exportKassenmeldungPdf() {
        viewModelScope.launch {
            runCatchingApi { fiscalConfigurationApiClient.downloadKassenMeldungPdf(1L) }
                .onSuccess { data ->
                    FileSaver.saveFile(
                        data = data,
                        filename = "kassenmeldung.pdf",
                        mimeType = "application/pdf",
                        onSuccess = { path ->
                            _uiState.update {
                                it.copy(
                                    notice =
                                        SettingsNotice(
                                            title = DatevSettingsKeys.exportSuccess,
                                            message = DatevSettingsKeys.kassenmeldungPdfExported,
                                            args = listOf(path),
                                        ),
                                )
                            }
                        },
                        onError = { error ->
                            logger.e { "Failed to save Kassenmeldung PDF: $error" }
                        },
                    )
                }.onError { uiError ->
                    _uiState.update { it.copy(error = uiError) }
                }
        }
    }

    // ========== DATEV XML Config Operations ==========

    /**
     * Toggle DATEV XML config section expansion.
     * Loads config when expanding for the first time.
     */
    fun toggleDatevXmlConfigSection() {
        val wasExpanded = _uiState.value.datevXmlConfigState.isExpanded
        _uiState.update { state ->
            state.copy(
                datevXmlConfigState =
                    state.datevXmlConfigState.copy(
                        isExpanded = !wasExpanded,
                    ),
            )
        }
        if (!wasExpanded) {
            loadDatevXmlConfig()
        }
    }

    /**
     * Load DATEV XML configuration from server.
     */
    fun loadDatevXmlConfig() {
        logger.d { "DatevSettingsViewModel.loadDatevXmlConfig: loading DATEV XML config" }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    datevXmlConfigState = it.datevXmlConfigState.copy(isLoading = true),
                )
            }

            runCatchingApi { accountingConfigurationApiClient.getDatevXmlConfig(1L) }
                .onSuccess { config ->
                    _uiState.update {
                        it.copy(
                            datevXmlConfigState = it.datevXmlConfigState.withConfig(config).copy(isLoading = false),
                        )
                    }
                    loadDatevJobStatus()
                }.onError { uiError ->
                    _uiState.update {
                        it.copy(
                            error = uiError,
                            datevXmlConfigState = it.datevXmlConfigState.copy(isLoading = false),
                        )
                    }
                }
        }
    }

    /**
     * Update DATEV XML config field locally.
     */
    fun updateDatevXmlKassenName(value: String) {
        _uiState.update { it.copy(datevXmlConfigState = it.datevXmlConfigState.copy(kassenName = value)) }
    }

    fun updateDatevXmlExportPath(value: String) {
        _uiState.update { it.copy(datevXmlConfigState = it.datevXmlConfigState.copy(exportPath = value)) }
    }

    fun updateDatevXmlConsultantNumber(value: String) {
        _uiState.update { it.copy(datevXmlConfigState = it.datevXmlConfigState.copy(consultantNumber = value)) }
    }

    fun updateDatevXmlClientNumber(value: String) {
        _uiState.update { it.copy(datevXmlConfigState = it.datevXmlConfigState.copy(clientNumber = value)) }
    }

    fun updateDatevXmlSammeldebitor(value: String) {
        _uiState.update { it.copy(datevXmlConfigState = it.datevXmlConfigState.copy(sammeldebitor = value)) }
    }

    fun updateDatevApExportMode(value: String) {
        _uiState.update { it.copy(datevXmlConfigState = it.datevXmlConfigState.copy(apExportMode = value)) }
    }

    fun updateWirtschaftsjahrBeginn(value: String) {
        _uiState.update { it.copy(datevXmlConfigState = it.datevXmlConfigState.copy(wirtschaftsjahrBeginn = value)) }
    }

    fun updateSachkontenlaenge(value: Int) {
        _uiState.update {
            it.copy(datevXmlConfigState = it.datevXmlConfigState.copy(sachkontenlaenge = value.coerceIn(4, 8)))
        }
    }

    fun updateFestschreibung(value: Boolean) {
        _uiState.update { it.copy(datevXmlConfigState = it.datevXmlConfigState.copy(festschreibung = value)) }
    }

    fun onDatevAutoEnabledChange(value: Boolean) {
        _uiState.update { it.copy(datevXmlConfigState = it.datevXmlConfigState.copy(enabled = value)) }
    }

    fun onKassenbuchCadenceChange(value: DatevAutoCadence) {
        _uiState.update { it.copy(datevXmlConfigState = it.datevXmlConfigState.copy(kassenbuchCadence = value)) }
    }

    fun onApLedgerCadenceChange(value: DatevAutoCadence) {
        _uiState.update { it.copy(datevXmlConfigState = it.datevXmlConfigState.copy(apLedgerCadence = value)) }
    }

    fun onBuchungsstapelCadenceChange(value: DatevAutoCadence) {
        _uiState.update { it.copy(datevXmlConfigState = it.datevXmlConfigState.copy(buchungsstapelCadence = value)) }
    }

    fun onDatevRunHourChange(value: Int) {
        _uiState.update {
            it.copy(datevXmlConfigState = it.datevXmlConfigState.copy(runHour = value.coerceIn(0, 23)))
        }
    }

    /**
     * Load the DATEV_AUTO_EXPORT scheduled job status. Delegates to the shared poller so
     * Backup + DATEV sections share a single /api/scheduled-jobs request per cycle.
     */
    fun loadDatevJobStatus() {
        viewModelScope.launch { refreshScheduledJobStatuses() }
    }

    /**
     * Single source of truth for scheduled-job status. Both the Backup and DATEV sections
     * call this; one HTTP request updates both state slots.
     */
    private suspend fun refreshScheduledJobStatuses() {
        runCatchingApi { scheduledJobApiClient.list() }
            .onSuccess { jobs ->
                val backupJob = jobs.firstOrNull { it.type == ScheduledJobType.BACKUP }
                val datevJob = jobs.firstOrNull { it.type == ScheduledJobType.DATEV_AUTO_EXPORT }
                _uiState.update {
                    it.copy(
                        backupState = it.backupState.copy(backupJobStatus = backupJob),
                        datevXmlConfigState = it.datevXmlConfigState.copy(datevJobStatus = datevJob),
                    )
                }
            }.onError { _ ->
                // Best-effort polling; don't surface as a fatal error.
            }
    }

    /**
     * Trigger an ad-hoc run of the DATEV_AUTO_EXPORT scheduled job.
     */
    fun onRunDatevNow() {
        val jobId =
            _uiState.value.datevXmlConfigState.datevJobStatus
                ?.id ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(datevXmlConfigState = it.datevXmlConfigState.copy(runningDatevJob = true))
            }
            runCatchingApi { scheduledJobApiClient.runNow(jobId) }
                .onSuccess { _ ->
                    _uiState.update {
                        it.copy(datevXmlConfigState = it.datevXmlConfigState.copy(runningDatevJob = false))
                    }
                    loadDatevJobStatus()
                }.onError { uiError ->
                    _uiState.update {
                        it.copy(
                            error = uiError,
                            datevXmlConfigState = it.datevXmlConfigState.copy(runningDatevJob = false),
                        )
                    }
                }
        }
    }

    /**
     * Save DATEV XML configuration to server.
     */
    fun saveDatevXmlConfig() {
        logger.i { "DatevSettingsViewModel.saveDatevXmlConfig: saving DATEV XML config" }
        val state = _uiState.value.datevXmlConfigState

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    datevXmlConfigState = it.datevXmlConfigState.copy(isSaving = true),
                )
            }

            val request =
                ShopConfigurationDto.UpdateDatevXmlConfigRequestDto(
                    kassenName = state.kassenName,
                    exportPath = state.exportPath,
                    consultantNumber = state.consultantNumber,
                    clientNumber = state.clientNumber,
                    sammeldebitor = state.sammeldebitor,
                    apExportMode = state.apExportMode,
                    wirtschaftsjahrBeginn = state.wirtschaftsjahrBeginn,
                    sachkontenlaenge = state.sachkontenlaenge,
                    festschreibung = state.festschreibung,
                    enabled = state.enabled,
                    kassenbuchCadence = state.kassenbuchCadence,
                    apLedgerCadence = state.apLedgerCadence,
                    buchungsstapelCadence = state.buchungsstapelCadence,
                    runHour = state.runHour,
                )

            runCatchingApi { accountingConfigurationApiClient.updateDatevXmlConfig(1L, request) }
                .onSuccess { config ->
                    _uiState.update {
                        it.copy(
                            savedMessage = DatevSettingsKeys.datevXmlConfigSaved,
                            datevXmlConfigState = it.datevXmlConfigState.withConfig(config).copy(isSaving = false),
                        )
                    }
                    loadDatevJobStatus()
                }.onError { uiError ->
                    _uiState.update {
                        it.copy(
                            error = uiError,
                            datevXmlConfigState = it.datevXmlConfigState.copy(isSaving = false),
                        )
                    }
                }
        }
    }

    /**
     * Open directory browser for DATEV XML export path.
     */
    fun openDatevXmlDirectoryBrowser() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    datevXmlConfigState = it.datevXmlConfigState.copy(showDirectoryBrowser = true),
                )
            }
            val startPath =
                _uiState.value.datevXmlConfigState.exportPath
                    .takeIf { it.isNotBlank() }
            loadDatevXmlDirectory(startPath)
        }
    }

    /**
     * Close directory browser for DATEV XML export path.
     */
    fun closeDatevXmlDirectoryBrowser() {
        _uiState.update { state ->
            state.copy(
                datevXmlConfigState =
                    state.datevXmlConfigState.copy(
                        showDirectoryBrowser = false,
                        directoryEntries = emptyList(),
                    ),
            )
        }
    }

    /**
     * Load directory contents for DATEV XML directory browser.
     */
    fun loadDatevXmlDirectory(path: String?) {
        viewModelScope.launch {
            runCatchingApi { shopConfigurationApiClient.listDirectories(path) }
                .onSuccess { data ->
                    _uiState.update {
                        it.copy(
                            datevXmlConfigState =
                                it.datevXmlConfigState.copy(
                                    currentBrowsePath = data.currentPath,
                                    parentBrowsePath = data.parentPath,
                                    currentPathWritable = data.canWrite,
                                    directoryEntries =
                                        data.entries.map { entry ->
                                            DirectoryEntry(
                                                name = entry.name,
                                                path = entry.path,
                                                isDirectory = entry.isDirectory,
                                                isReadable = entry.isReadable,
                                                isWritable = entry.isWritable,
                                            )
                                        },
                                ),
                        )
                    }
                }.onError { uiError ->
                    if (path != null) {
                        logger.w { "Directory not found: $path, falling back to home directory" }
                        loadDatevXmlDirectory(null)
                    } else {
                        _uiState.update { it.copy(error = uiError) }
                    }
                }
        }
    }

    /**
     * Navigate to parent in DATEV XML directory browser.
     */
    fun navigateDatevXmlToParent() {
        val parentPath = _uiState.value.datevXmlConfigState.parentBrowsePath
        if (parentPath != null) {
            loadDatevXmlDirectory(parentPath)
        }
    }

    /**
     * Select directory from browser and set as export path.
     */
    fun selectDatevXmlDirectory(path: String) {
        _uiState.update { state ->
            state.copy(
                datevXmlConfigState =
                    state.datevXmlConfigState.copy(
                        exportPath = path,
                        showDirectoryBrowser = false,
                        directoryEntries = emptyList(),
                    ),
            )
        }
    }

    // ========== Backup/Restore Operations ==========

    /**
     * Toggle Backup section expansion.
     */
    fun toggleBackupSection() {
        val wasExpanded = _uiState.value.backupState.isExpanded
        _uiState.update { state ->
            state.copy(
                backupState =
                    state.backupState.copy(
                        isExpanded = !wasExpanded,
                    ),
            )
        }
        // Load config and backups when expanding
        if (!wasExpanded) {
            loadBackupConfig()
            loadAvailableBackups()
        }
    }

    /**
     * Load backup configuration from server.
     */
    fun loadBackupConfig() {
        if (!canManageBackups()) {
            logger.d { "DatevSettingsViewModel.loadBackupConfig: skipped, no BACKUP_CREATE" }
            return
        }
        logger.d { "DatevSettingsViewModel.loadBackupConfig: loading backup config" }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    backupState = it.backupState.copy(isLoadingConfig = true),
                )
            }

            runCatchingApi { backupApiClient.getBackupConfig() }
                .onSuccess { data ->
                    _uiState.update {
                        it.copy(
                            backupState = it.backupState.withConfig(data).copy(isLoadingConfig = false),
                        )
                    }
                    loadAvailableBackups()
                    loadBackupJobStatus()
                }.onError { uiError ->
                    _uiState.update {
                        it.copy(
                            error = uiError,
                            backupState = it.backupState.copy(isLoadingConfig = false),
                        )
                    }
                }
        }
    }

    /**
     * Update default backup path.
     */
    fun updateDefaultBackupPath(path: String) {
        _uiState.update { state ->
            state.copy(
                backupState =
                    state.backupState.copy(
                        defaultBackupPath = path,
                    ),
            )
        }
    }

    /**
     * Toggle auto-backup enabled flag (local; persisted via [saveBackupConfig]).
     */
    fun onAutoEnabledChange(enabled: Boolean) {
        _uiState.update { it.copy(backupState = it.backupState.copy(autoEnabled = enabled)) }
    }

    /**
     * Update auto-backup cadence (local; persisted via [saveBackupConfig]).
     */
    fun onCadenceChange(cadence: BackupRestoreDto.BackupCadence) {
        _uiState.update { it.copy(backupState = it.backupState.copy(cadence = cadence)) }
    }

    /**
     * Update retention count (1..365). Coerced to range.
     */
    fun onRetentionChange(count: Int) {
        _uiState.update {
            it.copy(backupState = it.backupState.copy(retentionCount = count.coerceIn(1, 365)))
        }
    }

    /**
     * Update run hour (0..23). Coerced to range.
     */
    fun onRunHourChange(hour: Int) {
        _uiState.update {
            it.copy(backupState = it.backupState.copy(runHour = hour.coerceIn(0, 23)))
        }
    }

    /**
     * Update max upload bytes. Minimum 1 MB.
     */
    fun onMaxUploadBytesChange(bytes: Long) {
        _uiState.update {
            it.copy(backupState = it.backupState.copy(maxUploadBytes = bytes.coerceAtLeast(1024 * 1024)))
        }
    }

    /** Update the second backup target path (local; persisted via [saveBackupConfig]). */
    fun onCopyToPathChange(path: String) {
        _uiState.update { it.copy(backupState = it.backupState.copy(copyToPath = path)) }
    }

    /** Update the text of the "archives kept on the second target" field (1..365, see [BackupRestoreState.withCopyRetentionInput]). */
    fun onCopyRetentionInputChange(text: String) {
        _uiState.update { it.copy(backupState = it.backupState.withCopyRetentionInput(text)) }
    }

    /**
     * Load the BACKUP scheduled job status. Delegates to the shared poller; see
     * [refreshScheduledJobStatuses] — Backup + DATEV share one HTTP request.
     */
    fun loadBackupJobStatus() {
        viewModelScope.launch { refreshScheduledJobStatuses() }
    }

    /**
     * Trigger an ad-hoc run of the BACKUP scheduled job.
     */
    fun onRunBackupNow() {
        val jobId =
            _uiState.value.backupState.backupJobStatus
                ?.id ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(backupState = it.backupState.copy(runningBackupJob = true))
            }
            runCatchingApi { scheduledJobApiClient.runNow(jobId) }
                .onSuccess { _ ->
                    _uiState.update {
                        it.copy(backupState = it.backupState.copy(runningBackupJob = false))
                    }
                    loadBackupJobStatus()
                    refreshBackupRunRecord()
                }.onError { uiError ->
                    _uiState.update {
                        it.copy(
                            error = uiError,
                            backupState = it.backupState.copy(runningBackupJob = false),
                        )
                    }
                }
        }
    }

    /** Re-reads the last backup and copy record after a job run, leaving unsaved fields alone. */
    private fun refreshBackupRunRecord() {
        viewModelScope.launch {
            runCatchingApi { backupApiClient.getBackupConfig() }
                .onSuccess { data ->
                    _uiState.update { it.copy(backupState = it.backupState.withRunRecord(data)) }
                }
        }
    }

    /**
     * Save backup configuration to server.
     */
    fun saveBackupConfig() {
        logger.i { "DatevSettingsViewModel.saveBackupConfig: saving backup config" }
        val current = _uiState.value.backupState
        val currentPath = current.defaultBackupPath.takeIf { it.isNotBlank() }
        if (current.copyRetentionInvalid) {
            logger.w { "DatevSettingsViewModel.saveBackupConfig: retention field is empty, not sent" }
            return
        }
        if (current.copyPathInvalid) {
            // Same rule as the server (BackupCopyPathRules): refuse before sending.
            logger.w { "DatevSettingsViewModel.saveBackupConfig: copy path refused, not sent" }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    backupState = it.backupState.copy(isLoadingConfig = true),
                )
            }

            val request =
                BackupRestoreDto.UpdateBackupConfigDto(
                    defaultBackupPath = currentPath,
                    autoEnabled = current.autoEnabled,
                    cadence = current.cadence,
                    retentionCount = current.retentionCount,
                    runHour = current.runHour,
                    maxUploadBytes = current.maxUploadBytes,
                    copyTo = current.copyTargetForSave(),
                    copyRetentionCount = current.copyRetentionCount,
                )

            runCatchingApi { backupApiClient.updateBackupConfig(request) }
                .onSuccess { data ->
                    _uiState.update {
                        it.copy(
                            savedMessage = DatevSettingsKeys.backupConfigSaved,
                            backupState = it.backupState.withConfig(data).copy(isLoadingConfig = false),
                        )
                    }
                    // Reload backups for new path
                    loadAvailableBackups()
                    loadBackupJobStatus()
                }.onError { uiError ->
                    _uiState.update {
                        it.copy(
                            error = uiError,
                            backupState = it.backupState.copy(isLoadingConfig = false),
                        )
                    }
                }
        }
    }

    /**
     * Open directory browser dialog.
     */
    fun openDirectoryBrowser() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    backupState = it.backupState.copy(showDirectoryBrowser = true),
                )
            }
            // Load initial directory (home or current default)
            val startPath =
                _uiState.value.backupState.defaultBackupPath
                    .takeIf { it.isNotBlank() }
            loadDirectory(startPath)
        }
    }

    /**
     * Close directory browser dialog.
     */
    fun closeDirectoryBrowser() {
        _uiState.update { state ->
            state.copy(
                backupState =
                    state.backupState.copy(
                        showDirectoryBrowser = false,
                        directoryEntries = emptyList(),
                    ),
            )
        }
    }

    /**
     * Load directory contents for browser.
     */
    fun loadDirectory(path: String?) {
        viewModelScope.launch {
            runCatchingApi { backupApiClient.listDirectories(path) }
                .onSuccess { data ->
                    _uiState.update {
                        it.copy(
                            backupState =
                                it.backupState.copy(
                                    currentBrowsePath = data.currentPath,
                                    parentBrowsePath = data.parentPath,
                                    currentPathWritable = data.canWrite,
                                    directoryEntries =
                                        data.entries.map { entry ->
                                            DirectoryEntry(
                                                name = entry.name,
                                                path = entry.path,
                                                isDirectory = entry.isDirectory,
                                                isReadable = entry.isReadable,
                                                isWritable = entry.isWritable,
                                            )
                                        },
                                ),
                        )
                    }
                }.onError { uiError ->
                    if (path != null) {
                        logger.w { "Directory not found: $path, falling back to home directory" }
                        loadDirectory(null)
                    } else {
                        _uiState.update { it.copy(error = uiError) }
                    }
                }
        }
    }

    /**
     * Navigate to parent directory in browser.
     */
    fun navigateToParent() {
        val parentPath = _uiState.value.backupState.parentBrowsePath
        if (parentPath != null) {
            loadDirectory(parentPath)
        }
    }

    /**
     * Select directory from browser and set as default path.
     */
    fun selectDirectory(path: String) {
        _uiState.update { state ->
            state.copy(
                backupState =
                    state.backupState.copy(
                        defaultBackupPath = path,
                        showDirectoryBrowser = false,
                        directoryEntries = emptyList(),
                    ),
            )
        }
    }

    /**
     * Create database backup to server path.
     */
    fun createBackup() {
        val currentState = _uiState.value.backupState
        if (!currentState.canCreateBackup()) return
        logger.i { "DatevSettingsViewModel.createBackup: creating backup to path=${currentState.defaultBackupPath}" }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    backupState = it.backupState.copy(isCreatingBackup = true),
                )
            }

            val request =
                org.example.project.core.domain.dto.backup.BackupRestoreDto.CreateBackupToServerRequestDto(
                    savePath = currentState.defaultBackupPath.takeIf { it.isNotBlank() },
                )

            runCatchingApi { backupApiClient.createBackupToServer(request) }
                .onSuccess { data ->
                    _uiState.update {
                        it.copy(
                            notice =
                                SettingsNotice(
                                    title = DatevSettingsKeys.backupCreatedTitle,
                                    message = DatevSettingsKeys.backupCreatedMessage,
                                    args = listOf(data.filePath),
                                ),
                            backupState = it.backupState.copy(isCreatingBackup = false),
                        )
                    }
                    // Reload config and backups
                    loadBackupConfig()
                    loadAvailableBackups()
                }.onError { uiError ->
                    _uiState.update {
                        it.copy(
                            error = uiError,
                            backupState = it.backupState.copy(isCreatingBackup = false),
                        )
                    }
                }
        }
    }

    /**
     * Load available backup files from configured directory.
     */
    fun loadAvailableBackups() {
        if (!canManageBackups()) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    backupState = it.backupState.copy(isLoadingBackups = true),
                )
            }

            val directory =
                _uiState.value.backupState.defaultBackupPath
                    .takeIf { it.isNotBlank() }

            runCatchingApi { backupApiClient.listBackups(directory) }
                .onSuccess { resp ->
                    _uiState.update {
                        it.copy(
                            backupState =
                                it.backupState.copy(
                                    availableBackups =
                                        resp.backups.map { backup ->
                                            BackupFile(
                                                filename = backup.filename,
                                                filePath = backup.filePath,
                                                sizeBytes = backup.sizeBytes,
                                                createdAt = backup.createdAt,
                                            )
                                        },
                                    isLoadingBackups = false,
                                ),
                        )
                    }
                }.onError { _ ->
                    _uiState.update {
                        it.copy(
                            backupState =
                                it.backupState.copy(
                                    availableBackups = emptyList(),
                                    isLoadingBackups = false,
                                ),
                        )
                    }
                }
        }
    }

    /**
     * Select a backup file for restoration.
     */
    fun selectRestoreFile(filePath: String) {
        _uiState.update { state ->
            state.copy(
                backupState =
                    state.backupState.copy(
                        selectedRestoreFilePath = filePath,
                        showRestoreConfirmation = true,
                    ),
            )
        }
    }

    /**
     * Cancel restore operation and clear selection.
     */
    fun cancelRestore() {
        _uiState.update { state ->
            state.copy(
                backupState =
                    state.backupState.copy(
                        selectedRestoreFilePath = null,
                        showRestoreConfirmation = false,
                    ),
            )
        }
    }

    /**
     * Confirm and execute database restore from server file.
     */
    fun confirmRestore() {
        val filePath = _uiState.value.backupState.selectedRestoreFilePath ?: return
        logger.i { "DatevSettingsViewModel.confirmRestore: restoring from filePath=$filePath" }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    backupState =
                        it.backupState.copy(
                            isRestoring = true,
                            showRestoreConfirmation = false,
                        ),
                )
            }

            runCatchingApi { backupApiClient.restoreFromServerFile(filePath) }
                .onSuccess { _ ->
                    // The server's own message is English free text; the file restored from is what
                    // the operator needs, and it is already known here (F63).
                    _uiState.update {
                        it.copy(
                            notice =
                                SettingsNotice(
                                    title = DatevSettingsKeys.restoreCompletedTitle,
                                    message = DatevSettingsKeys.restoreCompletedMessage,
                                    args = listOf(filePath),
                                ),
                            backupState =
                                it.backupState.copy(
                                    isRestoring = false,
                                    selectedRestoreFilePath = null,
                                ),
                        )
                    }
                }.onError { uiError ->
                    _uiState.update {
                        it.copy(
                            error = uiError,
                            backupState =
                                it.backupState.copy(
                                    isRestoring = false,
                                    selectedRestoreFilePath = null,
                                ),
                        )
                    }
                }
        }
    }

    // ========== Common Operations ==========

    /**
     * Clear error state.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /** Clear the backup/restore/export confirmation once it has been shown. */
    fun clearNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    /** Clear the saved confirmation once it has been shown. */
    fun clearSavedMessage() {
        _uiState.update { it.copy(savedMessage = null) }
    }

    // ========== DATEV Account Mapping Operations ==========

    fun loadDatevAccountMapping() {
        viewModelScope.launch {
            _uiState.update { it.copy(accountMappingState = it.accountMappingState.copy(isLoading = true)) }
            // A failed payment-type fetch must abort the load: proceeding with an empty
            // list would render (and later full-replace-save) a mapping without payment
            // rows, silently wiping previously saved cells.
            val nonCash =
                runCatchingApi { paymentTypeApiClient.getAllPaymentTypes() }
                    .onError { uiError ->
                        _uiState.update {
                            it.copy(error = uiError, accountMappingState = it.accountMappingState.copy(isLoading = false))
                        }
                    }.getOrNull()
                    ?.paymentTypes
                    ?.filter { !it.isCash && it.isActive }
                    ?: return@launch
            runCatchingApi { accountingConfigurationApiClient.getDatevAccountMapping(1L) }
                .onSuccess { response ->
                    _uiState.update { state ->
                        state.copy(
                            accountMappingState =
                                response.mapping
                                    ?.toUiState(nonCash.map { Triple(it.id, it.name, it.isVoucher) })
                                    ?.copy(isLoading = false, configured = true, loaded = true)
                                    ?.pinned()
                                    ?: unconfiguredAccountMapping(state.accountMappingState, nonCash.map { Triple(it.id, it.name, it.isVoucher) }),
                        )
                    }
                }.onError { uiError ->
                    _uiState.update {
                        it.copy(error = uiError, accountMappingState = it.accountMappingState.copy(isLoading = false))
                    }
                }
        }
    }

    /** Leave-confirmation Discard: restores the last loaded or saved mapping without a network call. */
    fun discardAccountMappingEdits() = _uiState.update { it.copy(accountMappingState = it.accountMappingState.withEditsDiscarded()) }

    /** Leave-confirmation Discard: restores the last loaded or saved DATEV XML config without a network call. */
    fun discardDatevXmlConfigEdits() = _uiState.update { it.copy(datevXmlConfigState = it.datevXmlConfigState.withEditsDiscarded()) }

    /** Leave-confirmation Discard: restores the last loaded or saved backup config without a network call. */
    fun discardBackupConfigEdits() = _uiState.update { it.copy(backupState = it.backupState.withConfigDiscarded()) }

    fun onAccountMappingKontenrahmenSelected(kontenrahmen: Kontenrahmen) =
        _uiState.update { it.copy(accountMappingState = it.accountMappingState.copy(kontenrahmen = kontenrahmen)) }

    fun requestApplyAccountMappingDefaults() = _uiState.update { it.copy(accountMappingState = it.accountMappingState.copy(showSeedConfirm = true)) }

    fun cancelApplyAccountMappingDefaults() = _uiState.update { it.copy(accountMappingState = it.accountMappingState.copy(showSeedConfirm = false)) }

    fun applyAccountMappingDefaults() {
        val s = _uiState.value.accountMappingState
        val seed =
            DatevAccountMappingDefaults.seed(
                s.kontenrahmen,
                s.paymentTypes.map { DatevAccountMappingDefaults.PaymentTypeSeed(it.paymentTypeId, it.isVoucher) },
            )
        _uiState.update {
            it.copy(
                accountMappingState =
                    seed
                        .toUiState(s.paymentTypes.map { row -> Triple(row.paymentTypeId, row.name, row.isVoucher) })
                        // The seed is an edit, not a saved state: keep the snapshot the draft is compared with.
                        .copy(configured = s.configured, loaded = s.loaded, showSeedConfirm = false, saved = s.saved),
            )
        }
    }

    fun updateAccountMappingCell(
        isIncome: Boolean,
        kind: KassenbuchLineKind,
        rate: String,
        konto: String,
        bu: String,
    ) = _uiState.update { state ->
        val s = state.accountMappingState
        val side = (if (isIncome) s.income else s.expense).toMutableMap()
        val cells = side[kind].orEmpty().toMutableMap()
        cells[rate] = (cells[rate] ?: DatevAccountMappingCellState()).copy(konto = konto, bu = bu)
        side[kind] = cells
        state.copy(
            accountMappingState =
                if (isIncome) s.copy(income = side) else s.copy(expense = side),
        )
    }

    /**
     * Flips one rate cell's „Konto automatisch zuordnen" switch. Its Konto/BU are kept while it is
     * off (the fields are only disabled), so switching it back on restores them.
     */
    fun setAccountMappingCellAutoAssign(
        isIncome: Boolean,
        kind: KassenbuchLineKind,
        rate: String,
        autoAssign: Boolean,
    ) = _uiState.update { state ->
        val s = state.accountMappingState
        val side = (if (isIncome) s.income else s.expense).toMutableMap()
        val cells = side[kind].orEmpty().toMutableMap()
        cells[rate] = (cells[rate] ?: DatevAccountMappingCellState()).copy(autoAssign = autoAssign)
        side[kind] = cells
        state.copy(
            accountMappingState = if (isIncome) s.copy(income = side) else s.copy(expense = side),
        )
    }

    fun setAccountMappingPaymentAutoAssign(
        paymentTypeId: Long,
        autoAssign: Boolean,
    ) = _uiState.update { state ->
        val s = state.accountMappingState
        state.copy(
            accountMappingState =
                s.copy(
                    paymentTypes =
                        s.paymentTypes.map {
                            if (it.paymentTypeId == paymentTypeId) it.copy(cell = it.cell.copy(autoAssign = autoAssign)) else it
                        },
                ),
        )
    }

    fun setAccountMappingSpecialAutoAssign(
        slot: KassenbuchSpecialBooking,
        autoAssign: Boolean,
    ) = _uiState.update { state ->
        val s = state.accountMappingState
        val cell = s.special[slot] ?: DatevAccountMappingCellState()
        state.copy(accountMappingState = s.copy(special = s.special + (slot to cell.copy(autoAssign = autoAssign))))
    }

    /** „Alle Sonderbuchungen ohne Zuordnung": switches automatic assignment off for every special slot. */
    fun switchOffAllSpecialAutoAssign() =
        _uiState.update { state ->
            val s = state.accountMappingState
            val special =
                KassenbuchSpecialBooking.entries.associateWith { slot ->
                    (s.special[slot] ?: DatevAccountMappingCellState()).copy(autoAssign = false)
                }
            state.copy(accountMappingState = s.copy(special = special))
        }

    fun updateAccountMappingKasseKonto(value: String) = _uiState.update { it.copy(accountMappingState = it.accountMappingState.copy(kasseKonto = value)) }

    fun updateAccountMappingPaymentCell(
        paymentTypeId: Long,
        konto: String,
        bu: String,
    ) = _uiState.update { state ->
        val s = state.accountMappingState
        state.copy(
            accountMappingState =
                s.copy(
                    paymentTypes =
                        s.paymentTypes.map {
                            if (it.paymentTypeId == paymentTypeId) it.copy(cell = it.cell.copy(konto = konto, bu = bu)) else it
                        },
                ),
        )
    }

    fun updateAccountMappingSpecialCell(
        slot: KassenbuchSpecialBooking,
        konto: String,
        bu: String,
    ) = _uiState.update { state ->
        val s = state.accountMappingState
        val cell = s.special[slot] ?: DatevAccountMappingCellState()
        state.copy(
            accountMappingState = s.copy(special = s.special + (slot to cell.copy(konto = konto, bu = bu))),
        )
    }

    fun saveDatevAccountMapping() {
        val s = _uiState.value.accountMappingState

        // Pre-scan for malformed input BEFORE building the DTO: toMappingDto() silently
        // drops cells whose konto doesn't parse, so an unparseable-but-non-blank entry
        // would otherwise be lost without any feedback. Blank konto cells remain
        // legitimately omitted (= unmapped). A cell with automatic assignment switched off is
        // exported without an account, so whatever its fields hold is not checked (rule 8 mirror
        // of MappingDto.validate).
        fun cellInvalid(cell: DatevAccountMappingCellState): Boolean {
            if (!cell.autoAssign) return false
            val konto = cell.konto.trim()
            val bu = cell.bu.trim()
            return (konto.isNotBlank() && konto.toIntOrNull() == null) ||
                (bu.isNotBlank() && bu.length > 4)
        }
        val kasse = s.kasseKonto.trim()
        val hasInvalidInput =
            (kasse.isNotBlank() && kasse.toIntOrNull() == null) ||
                (s.income.values + s.expense.values).any { cells -> cells.values.any { cellInvalid(it) } } ||
                s.paymentTypes.any { cellInvalid(it.cell) } ||
                s.special.values.any { cellInvalid(it) }
        if (hasInvalidInput) {
            _uiState.update {
                it.copy(
                    accountMappingState =
                        it.accountMappingState.copy(
                            validationErrorKey = DatevSettingsKeys.mappingInvalidKonto,
                        ),
                )
            }
            return
        }
        // The server refuses a mapping with any special slot unset (MappingDto.validate). Name that
        // case on its own — the generic "invalid" message gave no hint which field was missing. Both
        // seeds fill all nine slots, but an operator can clear one, and a mapping stored before a
        // slot was seeded keeps its gap (seeds never rewrite a stored mapping).
        // A slot switched off may stay empty — that is the operator's explicit "leave it to DATEV".
        val specialMissing =
            KassenbuchSpecialBooking.entries.any { slot ->
                val cell = s.special[slot]
                cell == null || (cell.autoAssign && cell.konto.isBlank())
            }
        if (specialMissing) {
            _uiState.update {
                it.copy(accountMappingState = it.accountMappingState.copy(validationErrorKey = DatevSettingsKeys.mappingSpecialMissing))
            }
            return
        }
        val dto = s.toMappingDto()
        if (dto == null || dto.validate().isNotEmpty()) {
            _uiState.update {
                it.copy(
                    accountMappingState =
                        it.accountMappingState.copy(
                            validationErrorKey = DatevSettingsKeys.mappingValidationFailed,
                        ),
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(accountMappingState = it.accountMappingState.copy(isSaving = true, validationErrorKey = null)) }
            runCatchingApi { accountingConfigurationApiClient.updateDatevAccountMapping(1L, dto) }
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            savedMessage = DatevSettingsKeys.accountMappingSaved,
                            accountMappingState =
                                state.accountMappingState.copy(
                                    isSaving = false,
                                    configured = true,
                                    // What was submitted is now the saved state; later edits stay dirty.
                                    saved = s.copy(saved = null),
                                ),
                        )
                    }
                }.onError { uiError ->
                    _uiState.update {
                        it.copy(error = uiError, accountMappingState = it.accountMappingState.copy(isSaving = false))
                    }
                }
        }
    }
}

/**
 * A shop with no stored mapping: keeps whatever the operator already typed ([current]) but pins the
 * saved snapshot to the pristine empty mapping, so typed values count as unsaved edits.
 */
private fun unconfiguredAccountMapping(
    current: DatevAccountMappingState,
    paymentTypeInfo: List<Triple<Long, String, Boolean>>,
): DatevAccountMappingState {
    val rows = paymentTypeInfo.map { (id, name, isVoucher) -> DatevAccountMappingPaymentRow(id, name, isVoucher) }
    return current.copy(
        isLoading = false,
        configured = false,
        loaded = true,
        paymentTypes = rows,
        saved = DatevAccountMappingState(paymentTypes = rows),
    )
}

private fun DatevAccountMappingDto.MappingDto.toUiState(paymentTypeInfo: List<Triple<Long, String, Boolean>>): DatevAccountMappingState {
    fun cells(side: Map<KassenbuchLineKind, Map<String, DatevAccountMappingDto.AccountCell>>) =
        side.mapValues { (_, m) ->
            m.mapValues { (_, c) -> c.toCellState() }
        }
    // Union: fetched active non-cash types PLUS any ids already present in the stored
    // mapping that the fetch didn't return (e.g. deactivated payment types). Without
    // this, a full-replace save would silently drop cells the user couldn't see.
    val fetchedIds = paymentTypeInfo.map { it.first }.toSet()
    val orphanedRows =
        paymentTypes.keys
            .filter { it !in fetchedIds }
            .sorted()
            .map { id -> Triple(id, "#$id", false) }
    return DatevAccountMappingState(
        kontenrahmen = kontenrahmen,
        kasseKonto = kasseKonto.toString(),
        income = cells(income),
        expense = cells(expense),
        paymentTypes =
            (paymentTypeInfo + orphanedRows).map { (id, name, isVoucher) ->
                val cell = paymentTypes[id]
                DatevAccountMappingPaymentRow(
                    paymentTypeId = id,
                    name = name,
                    isVoucher = isVoucher,
                    cell = cell?.toCellState() ?: DatevAccountMappingCellState(),
                )
            },
        special = special.mapValues { (_, c) -> c.toCellState() },
    )
}

private fun DatevAccountMappingDto.AccountCell.toCellState() =
    DatevAccountMappingCellState(konto = konto?.toString().orEmpty(), bu = bu.orEmpty(), autoAssign = autoAssign)

/**
 * The stored cell for [this] UI cell, or null when it maps nothing (an ON cell with no parseable
 * account — omitted, as before). A switched-off cell is ALWAYS kept, empty or not: its presence
 * with `autoAssign = false` is the operator's decision, and dropping it would turn "leave it to
 * DATEV" into "unmapped", which the export refuses.
 */
private fun DatevAccountMappingCellState.toAccountCell(): DatevAccountMappingDto.AccountCell? {
    val parsed = konto.trim().toIntOrNull()
    val buOrNull = bu.trim().ifBlank { null }
    if (!autoAssign) return DatevAccountMappingDto.AccountCell(konto = parsed, bu = buOrNull, autoAssign = false)
    return parsed?.let { DatevAccountMappingDto.AccountCell(konto = it, bu = buOrNull) }
}

private fun DatevAccountMappingState.toMappingDto(): DatevAccountMappingDto.MappingDto? {
    fun side(m: Map<KassenbuchLineKind, Map<String, DatevAccountMappingCellState>>) =
        m
            .mapValues { (_, cells) ->
                cells
                    .mapNotNull { (rate, c) -> c.toAccountCell()?.let { rate to it } }
                    .toMap()
            }.filterValues { it.isNotEmpty() }
    val kasse = kasseKonto.trim().toIntOrNull() ?: return null
    return DatevAccountMappingDto.MappingDto(
        kontenrahmen = kontenrahmen,
        kasseKonto = kasse,
        income = side(income),
        expense = side(expense),
        paymentTypes =
            paymentTypes
                .mapNotNull { row -> row.cell.toAccountCell()?.let { row.paymentTypeId to it } }
                .toMap(),
        special =
            special
                .mapNotNull { (slot, c) -> c.toAccountCell()?.let { slot to it } }
                .toMap(),
    )
}
