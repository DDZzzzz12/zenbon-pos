package org.example.project.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Security
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.components.notification.DisplayMode
import org.example.project.core.presentation.components.notification.GlobalOverlayViewModel
import org.example.project.core.presentation.components.notification.LocalGlobalOverlayViewModel
import org.example.project.core.presentation.error.displayMessage
import org.example.project.core.presentation.navigation.ErrorState
import org.example.project.core.presentation.navigation.LoadingState
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.DatevSettingsKeys
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.keys.TseSettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.features.settings.presentation.components.tse.TseAdminOperationsSection
import org.example.project.features.settings.presentation.components.tse.TseAdminUnlockDialog
import org.example.project.features.settings.presentation.components.tse.TseConfigurationSection
import org.example.project.features.settings.presentation.components.tse.TseConnectionStatusCard
import org.example.project.features.settings.presentation.components.tse.TseDecommissionDialog
import org.example.project.features.settings.presentation.components.tse.TseFirmwareApplyDialog
import org.example.project.features.settings.presentation.components.tse.TseInformationSection
import org.example.project.features.settings.presentation.components.tse.TseLogEntryBrowserSection
import org.example.project.features.settings.presentation.components.tse.TseQueueMonitoringSection
import org.example.project.features.settings.presentation.components.tse.TseSetupDialog
import org.example.project.features.settings.presentation.state.OperationResult
import org.example.project.features.settings.presentation.state.TseSettingsUiState
import org.example.project.features.settings.presentation.viewmodel.TseSettingsViewModel
import org.example.project.ui.components.PosSuccessWatcher
import org.example.project.ui.screens.settings.components.PosDsfinvkExportSection
import org.example.project.ui.screens.settings.components.PosTseExportSection
import org.example.project.ui.screens.settings.components.SettingsContentHeader
import org.example.project.ui.screens.settings.components.SettingsSectionLabel
import org.example.project.ui.theme.PosSpacing
import org.koin.compose.viewmodel.koinViewModel

/**
 * @param stackColumns null (default) keeps the one-column layout for [isCompact] only, as before.
 *   adminApp passes the verdict of the width it has: true also stacks a 600-840dp window, whose two
 *   columns would be ~310dp each. The page header still follows [isCompact].
 * @param phoneLayout 48dp controls and the stacked DSFinV-K inputs for a phone; default off.
 */
@Composable
fun PosTseContent(
    language: Language,
    isCompact: Boolean,
    isAdminOrOwner: Boolean,
    viewModel: TseSettingsViewModel = koinViewModel(),
    onOpenTerminals: (() -> Unit)? = null,
    stackColumns: Boolean? = null,
    phoneLayout: Boolean = false,
) {
    val windowSize = LocalWindowSizeClass.current
    val uiState by viewModel.uiState.collectAsState()
    val overlay = LocalGlobalOverlayViewModel.current

    TseLoadingEffects(language, uiState, overlay, viewModel)

    Column(modifier = Modifier.fillMaxSize()) {
        if (!isCompact) {
            SettingsContentHeader(
                icon = Icons.Default.Security,
                title = str(SettingsKeys.tseTab),
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                uiState.isLoading -> {
                    LoadingState(message = getString(SettingsKeys.loading, language))
                }

                uiState.error != null && uiState.config == null -> {
                    ErrorState(
                        message =
                            uiState.error?.let { getString(it.messageKey, language, *it.messageArgs.toTypedArray()) } ?: "Unknown error",
                        onRetry = { viewModel.loadTseData() },
                        retryButtonText = getString(CommonKeys.refresh, language),
                    )
                }

                else -> {
                    if (stackColumns ?: isCompact) {
                        CompactTseContent(
                            language = language,
                            uiState = uiState,
                            viewModel = viewModel,
                            windowSize = windowSize,
                            isAdminOrOwner = isAdminOrOwner,
                            phoneLayout = phoneLayout,
                        )
                    } else {
                        DesktopTseContent(
                            language = language,
                            uiState = uiState,
                            viewModel = viewModel,
                            windowSize = windowSize,
                            isAdminOrOwner = isAdminOrOwner,
                        )
                    }
                }
            }
        }
    }

    // Decommission confirmation dialog
    if (uiState.showDecommissionDialog) {
        TseDecommissionDialog(
            language = language,
            confirmText = uiState.decommissionConfirmText,
            onConfirmTextChange = { viewModel.updateDecommissionConfirmText(it) },
            onConfirm = { viewModel.decommission() },
            onDismiss = { viewModel.hideDecommissionDialog() },
        )
    }

    // TSE administration unlock. Raised by any admin action the server refused for want of a
    // sitting — the Admin PIN is not stored, so the failure is the prompt.
    if (uiState.showAdminUnlockDialog) {
        TseAdminUnlockDialog(
            language = language,
            pin = uiState.adminUnlockPin,
            isUnlocking = uiState.isUnlockingAdmin,
            onPinChange = { viewModel.updateAdminUnlockPin(it) },
            onUnlock = { viewModel.unlockAdmin() },
            onDismiss = { viewModel.hideAdminUnlockDialog() },
        )
    }

    // TSE commissioning — collects the Admin PIN/PUK/seed for a single setup call.
    if (uiState.showSetupDialog) {
        TseSetupDialog(
            language = language,
            isSubmitting = uiState.isPerformingOperation,
            initialTimeAdminPin = uiState.formState.timeAdminPin,
            errorMessage = uiState.setupError?.displayMessage(),
            terminals = uiState.setupTerminals,
            isLoadingTerminals = uiState.isLoadingSetupTerminals,
            selectedTerminalId = uiState.selectedSetupTerminalId,
            onSelectTerminal = viewModel::selectSetupTerminal,
            onConfirm = { adminPin, adminPuk, seed, timeAdminPin -> viewModel.setupTse(adminPin, adminPuk, seed, timeAdminPin) },
            onDismiss = { viewModel.hideSetupDialog() },
            onOpenTerminals = onOpenTerminals,
        )
    }

    // Firmware apply confirmation dialog
    if (uiState.showFirmwareApplyDialog) {
        TseFirmwareApplyDialog(
            language = language,
            onConfirm = { viewModel.applyBundledFirmwareUpdate() },
            onDismiss = { viewModel.hideFirmwareApplyDialog() },
        )
    }
}

@Composable
private fun DesktopTseContent(
    language: Language,
    uiState: TseSettingsUiState,
    viewModel: TseSettingsViewModel,
    windowSize: androidx.window.core.layout.WindowSizeClass,
    isAdminOrOwner: Boolean,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(PosSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Left column: Connection Status + Connection Mode + Configuration + Information
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TseConnectionStatusCard(
                    language = language,
                    uiState = uiState,
                    onRefresh = { viewModel.refreshStatus() },
                    onRestart = { viewModel.restartTse() },
                )

                TseConfigurationSection(
                    language = language,
                    formState = uiState.formState,
                    isEditing = uiState.isEditing,
                    isSaving = uiState.isSaving,
                    canEdit = isAdminOrOwner,
                    windowSize = windowSize,
                    isTseInitialized = uiState.isTseInitialized(),
                    offerSetup = uiState.canOfferSetup(),
                    isPerformingOperation = uiState.isPerformingOperation,
                    onEdit = { viewModel.startEditing() },
                    onSave = {
                        viewModel.saveConfig {
                            viewModel.refreshStatus()
                        }
                    },
                    onCancel = { viewModel.cancelEditing() },
                    onFieldChange = viewModel::updateFormField,
                    onKeepAliveChange = viewModel::updateKeepAliveEnabled,
                    onSetup = { viewModel.showSetupDialog() },
                )

                if (uiState.isTseAvailable) {
                    uiState.tseInfo?.let { tseInfo ->
                        TseInformationSection(
                            language = language,
                            tseInfo = tseInfo,
                            status = uiState.status,
                            healthCheck = uiState.healthCheck,
                            flashHealth = uiState.flashHealth,
                            windowSize = windowSize,
                        )
                    }
                }
            }

            // Right column: Queue Monitoring + Log Browser + Admin Operations
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (isAdminOrOwner) {
                    TseQueueMonitoringSection(
                        language = language,
                        queueStatus = uiState.queueStatus,
                        queueEntries = uiState.queueEntries,
                        isLoadingStatus = uiState.isLoadingQueueStatus,
                        isLoadingEntries = uiState.isLoadingQueueEntries,
                        isExpanded = uiState.isQueueSectionExpanded,
                        currentFilter = uiState.queueEntriesFilter,
                        isPerforming = uiState.isPerformingOperation,
                        onToggleExpand = { viewModel.toggleQueueSection() },
                        onRefresh = { viewModel.refreshQueueData() },
                        onFilterChange = { viewModel.setQueueEntriesFilter(it) },
                        onPause = { viewModel.pauseQueueService() },
                        onResume = { viewModel.resumeQueueService() },
                        onTriggerRetry = { viewModel.triggerManualRetry() },
                        onCleanup = { viewModel.cleanupOldEntries() },
                        onRetryEntry = { viewModel.retryEntry(it) },
                        onCancelEntry = { id, reason -> viewModel.cancelEntry(id, reason) },
                        windowSize = windowSize,
                    )
                }

                if (isAdminOrOwner && uiState.isTseAvailable) {
                    TseLogEntryBrowserSection(
                        language = language,
                        logEntries = uiState.logEntries,
                        isLoading = uiState.isLoadingEntries,
                        isExpanded = uiState.isLogEntrySectionExpanded,
                        isPerforming = uiState.isPerformingOperation,
                        onToggleExpand = { viewModel.toggleLogEntrySection() },
                        onBrowseFirst = { viewModel.browseEntries("first") },
                        onBrowseLast = { viewModel.browseEntries("last") },
                        onBrowseById = { viewModel.browseEntries("byId", it) },
                        onExportForAnalysis = { viewModel.exportLogEntriesForAnalysis() },
                        windowSize = windowSize,
                    )
                }

                if (isAdminOrOwner) {
                    TseAdminOperationsSection(
                        language = language,
                        canPerform = uiState.canPerformOperations(),
                        isPerforming = uiState.isPerformingOperation,
                        isTseAvailable = uiState.isTseAvailable,
                        isDevelopmentFirmware = uiState.tseInfo?.isDevelopmentFirmware == true,
                        clients = uiState.clients,
                        isLoadingClients = uiState.isLoadingClients,
                        openTransactions = uiState.openTransactions,
                        isLoadingTransactions = uiState.isLoadingTransactions,
                        lastTransaction = uiState.lastTransaction,
                        isLoadingLastTransaction = uiState.isLoadingLastTransaction,
                        firmwareUpdateCheck = uiState.firmwareUpdateCheck,
                        isCheckingFirmware = uiState.isCheckingFirmware,
                        onSelfTest = { viewModel.runSelfTest() },
                        onSyncTime = { viewModel.updateTime() },
                        onHealthCheck = { viewModel.checkHealth() },
                        onFlashHealth = { viewModel.checkFlashHealth() },
                        onFirmwareCheck = { viewModel.checkFirmwareUpdate() },
                        onFactoryReset = { viewModel.factoryReset() },
                        isAdminUnlocked = uiState.isAdminUnlocked,
                        onLockAdmin = { viewModel.lockAdmin() },
                        onUnlockAdmin = { viewModel.showAdminUnlockDialog() },
                        onDecommission = { viewModel.showDecommissionDialog() },
                        onApplyBundledFirmware = { viewModel.showFirmwareApplyDialog() },
                        onDisableCspExport = { viewModel.disableExportIfCspTestFails() },
                        onExportLcm = { viewModel.exportLcm() },
                        onRefreshClients = { viewModel.loadClients() },
                        onRefreshTransactions = { viewModel.loadOpenTransactions() },
                        onRefreshLastTransaction = { viewModel.loadLastTransaction() },
                        windowSize = windowSize,
                    )
                }
            }
        }

        // Exports are wide tables: full width below the two columns, not stacked in the right one.
        if (isAdminOrOwner) {
            TseExportSections(language, uiState, viewModel, windowSize)
        }
    }
}

@Composable
private fun CompactTseContent(
    language: Language,
    uiState: TseSettingsUiState,
    viewModel: TseSettingsViewModel,
    windowSize: androidx.window.core.layout.WindowSizeClass,
    isAdminOrOwner: Boolean,
    phoneLayout: Boolean,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TseConnectionStatusCard(
            language = language,
            uiState = uiState,
            onRefresh = { viewModel.refreshStatus() },
            onRestart = { viewModel.restartTse() },
            phoneLayout = phoneLayout,
        )

        TseConfigurationSection(
            language = language,
            formState = uiState.formState,
            isEditing = uiState.isEditing,
            isSaving = uiState.isSaving,
            canEdit = isAdminOrOwner,
            windowSize = windowSize,
            isTseInitialized = uiState.isTseInitialized(),
            offerSetup = uiState.canOfferSetup(),
            isPerformingOperation = uiState.isPerformingOperation,
            onEdit = { viewModel.startEditing() },
            onSave = {
                viewModel.saveConfig {
                    viewModel.refreshStatus()
                }
            },
            onCancel = { viewModel.cancelEditing() },
            onFieldChange = viewModel::updateFormField,
            onKeepAliveChange = viewModel::updateKeepAliveEnabled,
            onSetup = { viewModel.showSetupDialog() },
        )

        if (uiState.isTseAvailable) {
            uiState.tseInfo?.let { tseInfo ->
                TseInformationSection(
                    language = language,
                    tseInfo = tseInfo,
                    status = uiState.status,
                    healthCheck = uiState.healthCheck,
                    flashHealth = uiState.flashHealth,
                    windowSize = windowSize,
                )
            }
        }

        if (isAdminOrOwner) {
            TseQueueMonitoringSection(
                language = language,
                queueStatus = uiState.queueStatus,
                queueEntries = uiState.queueEntries,
                isLoadingStatus = uiState.isLoadingQueueStatus,
                isLoadingEntries = uiState.isLoadingQueueEntries,
                isExpanded = uiState.isQueueSectionExpanded,
                currentFilter = uiState.queueEntriesFilter,
                isPerforming = uiState.isPerformingOperation,
                onToggleExpand = { viewModel.toggleQueueSection() },
                onRefresh = { viewModel.refreshQueueData() },
                onFilterChange = { viewModel.setQueueEntriesFilter(it) },
                onPause = { viewModel.pauseQueueService() },
                onResume = { viewModel.resumeQueueService() },
                onTriggerRetry = { viewModel.triggerManualRetry() },
                onCleanup = { viewModel.cleanupOldEntries() },
                onRetryEntry = { viewModel.retryEntry(it) },
                onCancelEntry = { id, reason -> viewModel.cancelEntry(id, reason) },
                windowSize = windowSize,
                phoneLayout = phoneLayout,
            )
        }

        if (isAdminOrOwner && uiState.isTseAvailable) {
            TseLogEntryBrowserSection(
                language = language,
                logEntries = uiState.logEntries,
                isLoading = uiState.isLoadingEntries,
                isExpanded = uiState.isLogEntrySectionExpanded,
                isPerforming = uiState.isPerformingOperation,
                onToggleExpand = { viewModel.toggleLogEntrySection() },
                onBrowseFirst = { viewModel.browseEntries("first") },
                onBrowseLast = { viewModel.browseEntries("last") },
                onBrowseById = { viewModel.browseEntries("byId", it) },
                onExportForAnalysis = { viewModel.exportLogEntriesForAnalysis() },
                windowSize = windowSize,
            )
        }

        if (isAdminOrOwner) {
            TseAdminOperationsSection(
                language = language,
                canPerform = uiState.canPerformOperations(),
                isPerforming = uiState.isPerformingOperation,
                isTseAvailable = uiState.isTseAvailable,
                isDevelopmentFirmware = uiState.tseInfo?.isDevelopmentFirmware == true,
                clients = uiState.clients,
                isLoadingClients = uiState.isLoadingClients,
                openTransactions = uiState.openTransactions,
                isLoadingTransactions = uiState.isLoadingTransactions,
                lastTransaction = uiState.lastTransaction,
                isLoadingLastTransaction = uiState.isLoadingLastTransaction,
                firmwareUpdateCheck = uiState.firmwareUpdateCheck,
                isCheckingFirmware = uiState.isCheckingFirmware,
                onSelfTest = { viewModel.runSelfTest() },
                onSyncTime = { viewModel.updateTime() },
                onHealthCheck = { viewModel.checkHealth() },
                onFlashHealth = { viewModel.checkFlashHealth() },
                onFirmwareCheck = { viewModel.checkFirmwareUpdate() },
                onFactoryReset = { viewModel.factoryReset() },
                isAdminUnlocked = uiState.isAdminUnlocked,
                onLockAdmin = { viewModel.lockAdmin() },
                onUnlockAdmin = { viewModel.showAdminUnlockDialog() },
                onDecommission = { viewModel.showDecommissionDialog() },
                onApplyBundledFirmware = { viewModel.showFirmwareApplyDialog() },
                onDisableCspExport = { viewModel.disableExportIfCspTestFails() },
                onExportLcm = { viewModel.exportLcm() },
                onRefreshClients = { viewModel.loadClients() },
                onRefreshTransactions = { viewModel.loadOpenTransactions() },
                onRefreshLastTransaction = { viewModel.loadLastTransaction() },
                windowSize = windowSize,
                phoneLayout = phoneLayout,
            )

            TseExportSections(language, uiState, viewModel, windowSize, phoneLayout)
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun TseExportSections(
    language: Language,
    uiState: TseSettingsUiState,
    viewModel: TseSettingsViewModel,
    windowSize: androidx.window.core.layout.WindowSizeClass,
    phoneLayout: Boolean = false,
) {
    // DSFinV-K and the TSE TAR export are TSE operations, so they live here rather than in the
    // DATEV export menu. The section composables carry their own date pickers and the delete
    // confirmation dialog.
    SettingsSectionLabel(
        icon = Icons.Default.Description,
        title = str(DatevSettingsKeys.dsfinvkTitle),
    )
    PosDsfinvkExportSection(
        language = language,
        state = uiState.dsfinvkState,
        onDateRangeChange = { start, end -> viewModel.updateDsfinvkDateRange(start, end) },
        onToggleTseTar = { viewModel.toggleIncludeTseTar() },
        onValidate = { viewModel.validateDsfinvk() },
        onLoadStatistics = { viewModel.loadDsfinvkStatistics() },
        onExport = { viewModel.exportDsfinvk() },
        phoneLayout = phoneLayout,
    )

    Spacer(Modifier.height(4.dp))

    SettingsSectionLabel(
        icon = Icons.Default.Security,
        title = str(DatevSettingsKeys.tseExportTitle),
    )
    PosTseExportSection(
        language = language,
        windowSize = windowSize,
        state = uiState.tseExportState,
        onToggleExpand = { viewModel.toggleTseExportSection() },
        onExportTypeChange = { viewModel.updateTseExportType(it) },
        onDateRangeChange = { start, end -> viewModel.updateTseExportDateRange(start, end) },
        onClientChange = { viewModel.selectTseExportClient(it) },
        onCheckSize = { viewModel.loadTseExportSize() },
        onExport = { viewModel.exportTse() },
        onRequestDelete = { viewModel.requestDeleteExportedData() },
        onConfirmDelete = { viewModel.confirmDeleteExportedData() },
        onCancelDelete = { viewModel.cancelDeleteExportedData() },
        isVerifyingExport = uiState.isVerifyingExport,
        verificationReport = uiState.verificationReport,
        onVerifyExport = { fileName -> viewModel.verifyExport(fileName) },
        onArchiveFileNameChange = { fileName -> viewModel.updateArchiveFileName(fileName) },
        onRegisterArchive = { viewModel.registerExportArchive() },
    )
}

@Composable
private fun TseLoadingEffects(
    language: Language,
    uiState: TseSettingsUiState,
    overlay: GlobalOverlayViewModel,
    viewModel: TseSettingsViewModel,
) {
    // Show loading notification for saving TSE configuration
    var savingLoadingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(uiState.isSaving) {
        if (uiState.isSaving) {
            savingLoadingId =
                overlay.showLoading(
                    title = "TSE",
                    message = getString(TseSettingsKeys.savingConfig, language),
                )
        } else {
            savingLoadingId?.let {
                overlay.dismissById(it)
                savingLoadingId = null
            }
        }
    }

    // Show loading notification for TSE admin operations
    var operationLoadingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(uiState.isPerformingOperation) {
        if (uiState.isPerformingOperation) {
            operationLoadingId =
                overlay.showLoading(
                    title = "TSE",
                    message = getString(TseSettingsKeys.performingOperation, language),
                )
        } else {
            operationLoadingId?.let {
                overlay.dismissById(it)
                operationLoadingId = null
            }
        }
    }

    // Show save success via global notification system
    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            overlay.showSuccess(
                title = "TSE",
                message = getString(SettingsKeys.saveSuccess, language),
                displayMode = DisplayMode.TOAST,
            )
            viewModel.clearSaveSuccess()
        }
    }

    // Surface the config-apply result for non-operational outcomes (offline / login-failed / invalid).
    // The plain saveSuccess toast above only fires for OPERATIONAL/DISABLED, so this
    // ensures the operator is never shown a false "saved" when the TSE did not become operational.
    LaunchedEffect(uiState.applyResult) {
        uiState.applyResult?.let { result ->
            when (result.effectiveApplyStatus) {
                TseConfigDto.TseModeApplyStatus.UNREACHABLE ->
                    overlay.showWarning(
                        title = "TSE",
                        message = getString(TseSettingsKeys.applySavedButOffline, language),
                        displayMode = DisplayMode.DIALOG,
                    )
                TseConfigDto.TseModeApplyStatus.AUTH_FAILED ->
                    overlay.showWarning(
                        title = "TSE",
                        message =
                            buildString {
                                val remaining = result.remainingRetries
                                if (!result.mayBeLocked && remaining != null) {
                                    append(
                                        getString(
                                            TseSettingsKeys.applyLoginFailedWithRetries,
                                            language,
                                            remaining.toString(),
                                        ),
                                    )
                                } else {
                                    append(getString(TseSettingsKeys.applyLoginFailed, language))
                                    if (result.mayBeLocked) {
                                        append(" ")
                                        append(getString(TseSettingsKeys.applyPukHint, language))
                                    }
                                }
                            },
                        displayMode = DisplayMode.DIALOG,
                    )
                TseConfigDto.TseModeApplyStatus.NOT_COMMISSIONED ->
                    // Not a fault: the device answers and needs setup, not a PIN.
                    overlay.showInfo(
                        title = "TSE",
                        message = getString(TseSettingsKeys.applyNotCommissioned, language),
                        displayMode = DisplayMode.DIALOG,
                    )
                TseConfigDto.TseModeApplyStatus.INVALID_CONFIG ->
                    overlay.showWarning(
                        title = "TSE",
                        message = getString(TseSettingsKeys.applyInvalidConfig, language),
                        displayMode = DisplayMode.DIALOG,
                    )
                TseConfigDto.TseModeApplyStatus.OPERATIONAL,
                TseConfigDto.TseModeApplyStatus.DISABLED,
                -> {
                    // Operational outcomes are surfaced by the saveSuccess toast above.
                }
            }
            viewModel.clearApplyResult()
        }
    }

    // Handle operation results via global notification system
    LaunchedEffect(uiState.operationResult) {
        uiState.operationResult?.let { result ->
            when (result) {
                is OperationResult.Success ->
                    overlay.showSuccess(
                        title = "TSE",
                        message = result.message,
                        displayMode = DisplayMode.TOAST,
                    )
                is OperationResult.Failure ->
                    overlay.showWarning(
                        title = "TSE",
                        message = result.message,
                        displayMode = DisplayMode.TOAST,
                    )
            }
            viewModel.clearOperationResult()
        }
    }

    // === WHO OWNS uiState.error =============================================================
    //
    // `TseSettingsUiState.error` is a SINGLE slot written by eleven sites across three unrelated
    // concerns: the initial load (config + tseInfo), the config save, and the DSFinV-K/TSE
    // export-verify-delete family. This effect is its ONE reader-and-shower. Do not add a second:
    // `PosTseErrorOwnershipTest` fails the build if another `LaunchedEffect(uiState.error)`
    // appears in this file.
    //
    // It replaced two effects both keyed on this slot — one unconditional, one gated on
    // `config != null`. With config loaded, a single failure enqueued TWO dialogs and the two
    // raced on `clearError()`; with config null, the unconditional one cleared the error out from
    // under the inline `ErrorState`, which is precisely what the other one's gate existed to
    // protect. Both bugs came from two owners, so there is now one.
    //
    // EVERY error raises the dialog. That is what carries the traceId (with its copy button), the
    // server's RFC 7807 `detail` and the error code, and what gets the failure recorded by
    // `ClientErrorReporter` — the inline `ErrorState` shows none of those, so gating the dialog on
    // `config != null` would leave a config-load 500 unquotable and unlogged, which is the exact
    // failure that started this. DIALOG is forced deliberately: the TSE is the fiscal signing
    // device, and the bus's contract reserves an override for "a fiscal flow that must not be
    // dismissible as a toast".
    //
    // CLEARING is what is conditional, because `config` says which surface the error still owes:
    //  * `config != null` — the screen has content and an ACTION failed (save, export, verify,
    //    delete). Transient: the dialog has shown it, so clear it.
    //  * `config == null` — the load failed, so the screen has no content and the inline
    //    `ErrorState` in `PosTseContent` renders from THIS slot as its empty state, with a Retry
    //    that calls `loadTseData()`. Clearing here would blank the screen behind the dialog.
    //    It is ended by the ViewModel instead: `loadTseData()` nulls the error when the retry
    //    starts, and again when the load succeeds.
    //
    // The two surfaces cannot disagree: the placeholder renders the same `UiError` this dialog
    // does, and only one of them is ever the *error report* — the inline one is a content
    // placeholder. Leaving the slot set is safe because the key is the error itself, so this
    // effect does not re-fire for an error it has already shown.
    //
    // Title is "TSE", matching the other overlay calls in this file. It deliberately no longer
    // reads "Export error": this dialog also carries load and save failures, which it mislabelled.
    // =========================================================================================
    LaunchedEffect(uiState.error) {
        val err = uiState.error ?: return@LaunchedEffect
        overlay.showError(
            error = err,
            title = "TSE",
            message = getString(err.messageKey, language, *err.messageArgs.toTypedArray()),
            displayMode = DisplayMode.DIALOG,
        )
        if (uiState.config != null) viewModel.clearError()
    }

    // Export progress + results (DSFinV-K, TSE TAR), moved here with the export sections.
    var dsfinvkLoadingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(uiState.dsfinvkState.isExporting) {
        if (uiState.dsfinvkState.isExporting) {
            dsfinvkLoadingId =
                overlay.showLoading(
                    title = "TSE",
                    message = getString(DatevSettingsKeys.exportingDsfinvk, language),
                )
        } else {
            dsfinvkLoadingId?.let {
                overlay.dismissById(it)
                dsfinvkLoadingId = null
            }
        }
    }
    var tseExportLoadingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(uiState.tseExportState.isExporting) {
        if (uiState.tseExportState.isExporting) {
            tseExportLoadingId =
                overlay.showLoading(
                    title = "TSE",
                    message = getString(DatevSettingsKeys.exportingTse, language),
                )
        } else {
            tseExportLoadingId?.let {
                overlay.dismissById(it)
                tseExportLoadingId = null
            }
        }
    }
    var tseDeleteLoadingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(uiState.tseExportState.isDeleting) {
        if (uiState.tseExportState.isDeleting) {
            tseDeleteLoadingId =
                overlay.showLoading(
                    title = "TSE",
                    message = getString(DatevSettingsKeys.deletingTseExport, language),
                )
        } else {
            tseDeleteLoadingId?.let {
                overlay.dismissById(it)
                tseDeleteLoadingId = null
            }
        }
    }
    // Success toast for an export/delete.
    PosSuccessWatcher(
        message = uiState.successMessage,
        title = getString(DatevSettingsKeys.exportSuccess, language),
        onClear = { viewModel.clearSuccessMessage() },
    )
    // An export failure raises no separate dialog here: it lands in `uiState.error` like every
    // other action failure and is shown by the single owner above, which its `config != null`
    // gate already covers. The retry this block used to offer called `clearError()`, which is
    // what the dialog's own Close button does — a second Close labelled "Wiederholen", telling an
    // operator a failed DSFinV-K export was being retried when nothing was. A real retry cannot
    // live on the shared slot, because one callback cannot know which of save / export / verify /
    // delete produced the error; the export sections carry their own retriggers.
}
