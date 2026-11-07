/**
 * TSE Settings ViewModel.
 *
 * Manages state and business logic for the TSE settings tab.
 * Handles configuration, status queries, and admin operations.
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.settings.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.project.core.data.remote.DsfinvkApiClient
import org.example.project.core.data.remote.TerminalApiClient
import org.example.project.core.data.remote.TseQueueApiClient
import org.example.project.core.data.remote.TseSettingsApiClient
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.TseSettingsKeys
import org.example.project.core.utils.FileSaver
import org.example.project.core.utils.TseCredentialPlaceholders
import org.example.project.features.settings.presentation.state.OperationResult
import org.example.project.features.settings.presentation.state.TseConfigFormState
import org.example.project.features.settings.presentation.state.TseExportType
import org.example.project.features.settings.presentation.state.TseSettingsUiState
import org.example.project.features.settings.presentation.state.TseSetupTerminalOption
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * ViewModel for TSE settings management.
 *
 * Provides state management, API integration, and business logic
 * for TSE configuration, status monitoring, and admin operations.
 */
class TseSettingsViewModel(
    private val tseSettingsApi: TseSettingsApiClient,
    private val tseQueueApi: TseQueueApiClient,
    private val dsfinvkApiClient: DsfinvkApiClient,
    private val terminalApi: TerminalApiClient,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TseSettingsUiState())
    val uiState: StateFlow<TseSettingsUiState> = _uiState.asStateFlow()

    init {
        loadTseData()
        // A sitting can outlive the screen, or lapse while it is open. Ask the server rather
        // than assuming locked — otherwise the indicator lies after a navigation.
        refreshAdminSessionStatus()
        initializeDsfinvkDates()
    }

    // ========================================
    // Data Loading
    // ========================================

    /**
     * Load all TSE data (config, status, info).
     */
    fun loadTseData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            // Load configuration first
            loadConfig()

            // Check TSE availability and load status/info
            checkTseAvailability()

            _uiState.update { it.copy(isLoading = false) }
        }
    }

    /**
     * Load TSE configuration from server.
     */
    private suspend fun loadConfig() {
        runCatchingApi { tseSettingsApi.getConfig() }
            .onSuccess { config ->
                _uiState.update {
                    it.copy(
                        config = config,
                        formState = TseConfigFormState.fromConfig(config),
                        error = null,
                    )
                }
            }.onError { uiError ->
                _uiState.update { it.copy(error = uiError) }
            }
    }

    /**
     * Check if TSE is available and load status/info if so.
     */
    private suspend fun checkTseAvailability() {
        runCatchingApi { tseSettingsApi.ping() }
            .onSuccess { resp ->
                val available = resp["available"] == true
                // Absent on older servers — treat only an explicit false as "no driver",
                // so a missing field does not hide LOCAL mode on an existing deployment.
                val sdkAvailable = resp["sdkAvailable"] != false
                _uiState.update { it.copy(isTseAvailable = available, isTseSdkAvailable = sdkAvailable) }

                if (available) {
                    loadStatus()
                    loadInfo()
                }
            }.onError { _ ->
                _uiState.update { it.copy(isTseAvailable = false) }
            }
    }

    /**
     * Refresh TSE status from TSE Connector Server.
     */
    fun refreshStatus() {
        viewModelScope.launch {
            loadStatus()
            loadInfo()
        }
    }

    /**
     * Load TSE status.
     */
    private suspend fun loadStatus() {
        runCatchingApi { tseSettingsApi.getStatus() }
            .onSuccess { status ->
                _uiState.update { it.copy(status = status) }
            }.onError { _ ->
                // Don't set error - status is optional
            }
    }

    /**
     * Load TSE detailed info.
     */
    private suspend fun loadInfo() {
        runCatchingApi { tseSettingsApi.getInfo() }
            .onSuccess { info ->
                _uiState.update { it.copy(tseInfo = info) }
            }.onError { uiError ->
                _uiState.update { it.copy(error = uiError) }
            }
    }

    // ========================================
    // Configuration Editing
    // ========================================

    /**
     * Enter edit mode for configuration.
     */
    fun startEditing() {
        _uiState.update { it.copy(isEditing = true, saveSuccess = false) }
    }

    /**
     * Cancel editing and reset form to saved values.
     */
    fun cancelEditing() {
        val config = _uiState.value.config
        _uiState.update {
            it.copy(
                isEditing = false,
                formState = if (config != null) TseConfigFormState.fromConfig(config) else TseConfigFormState(),
                saveSuccess = false,
            )
        }
    }

    /**
     * Update a form field value.
     */
    fun updateFormField(
        field: String,
        value: String,
    ) {
        _uiState.update { currentState ->
            val newFormState =
                when (field) {
                    "mode" -> currentState.formState.copy(mode = value)
                    "timeAdminPin" -> currentState.formState.copy(timeAdminPin = value)
                    "keepAliveIntervalSeconds" -> currentState.formState.copy(keepAliveIntervalSeconds = value)
                    "failureBehavior" -> currentState.formState.copy(failureBehavior = value)
                    else -> currentState.formState
                }
            currentState.copy(formState = newFormState, saveSuccess = false)
        }
    }

    /**
     * Update keep-alive enabled toggle.
     */
    fun updateKeepAliveEnabled(enabled: Boolean) {
        _uiState.update {
            it.copy(
                formState = it.formState.copy(keepAliveEnabled = enabled),
                saveSuccess = false,
            )
        }
    }

    /**
     * Save configuration changes.
     *
     * @param onFailure when given, a refused save is handed to it instead of the screen-wide [TseSettingsUiState.error]
     *   dialog — the setup flow reports it inside its own dialog and must also end its spinner.
     * @param surfaceApplyResult false when the save is a step of setup, which reports its own outcome:
     *   neither the apply-result dialog nor the "saved" toast is shown. Setup saves before it
     *   commissions, so on a fresh TSE that save always reports a not-yet-commissioned device — true,
     *   but not news to an operator commissioning it right now.
     */
    fun saveConfig(
        onFailure: ((UiError) -> Unit)? = null,
        surfaceApplyResult: Boolean = true,
        onSuccess: (suspend () -> Unit)? = null,
    ) {
        val request = _uiState.value.formState.toUpdateRequest(_uiState.value.isTseInitialized())
        // The server refuses any configuration without a TimeAdmin PIN (TSE_TIME_ADMIN_PIN_REQUIRED).
        // Say so before sending when the server reported none stored and the form adds none — a
        // SIMULATION save used to end in the generic "unexpected error" dialog (QA 2026-10-06).
        if (request.timeAdminPin == null && _uiState.value.config?.timeAdminPinConfigured == false) {
            val missingPin =
                UiError.Unknown(
                    titleKey = TseSettingsKeys.configurationSection,
                    messageKey = TseSettingsKeys.timeAdminPinRequiredForSave,
                )
            if (onFailure != null) {
                onFailure(missingPin)
            } else {
                _uiState.update { it.copy(saveSuccess = false, error = missingPin) }
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(saveSuccess = false, isSaving = true, error = null) }

            runCatchingApi { tseSettingsApi.updateConfig(request) }
                .onSuccess { result ->
                    // The config is always persisted; whether it became OPERATIONAL is reported by
                    // applyStatus. Only flag a plain "saved" success when the device is actually
                    // operational/disabled/remote — never on offline/auth-failed/invalid results.
                    val operationalApply = result.effectiveApplyStatus in OPERATIONAL_APPLY_STATUSES
                    _uiState.update {
                        it.copy(
                            config = result.config,
                            formState = TseConfigFormState.fromConfig(result.config),
                            isEditing = false,
                            isSaving = false,
                            saveSuccess = surfaceApplyResult && operationalApply,
                            applyResult = if (surfaceApplyResult) result else null,
                        )
                    }
                    onSuccess?.invoke()
                }.onError { uiError ->
                    if (onFailure != null) {
                        _uiState.update { it.copy(isSaving = false) }
                        onFailure(uiError)
                    } else {
                        _uiState.update { it.copy(isSaving = false, error = uiError) }
                    }
                }
        }
    }

    // ========================================
    // Admin Operations
    // ========================================

    /**
     * Run TSE self-test.
     */
    fun runSelfTest() {
        viewModelScope.launch {
            _uiState.update { it.copy(isPerformingOperation = true, operationResult = null) }

            runCatchingApi { tseSettingsApi.runSelfTest() }
                .onSuccess { data ->
                    _uiState.update {
                        it.copy(
                            isPerformingOperation = false,
                            operationResult =
                                if (data.success) {
                                    OperationResult.Success(data.message)
                                } else {
                                    OperationResult.Failure(data.message)
                                },
                        )
                    }
                    refreshStatus()
                }.onError { uiError ->
                    if (!promptUnlockIfLocked(uiError)) {
                        _uiState.update {
                            it.copy(
                                isPerformingOperation = false,
                                operationResult = OperationResult.Failure(uiError.toString()),
                            )
                        }
                    }
                }
        }
    }

    /**
     * Sync TSE time with system time.
     */
    fun updateTime() {
        viewModelScope.launch {
            _uiState.update { it.copy(isPerformingOperation = true, operationResult = null) }

            runCatchingApi { tseSettingsApi.updateTime() }
                .onSuccess { data ->
                    _uiState.update {
                        it.copy(
                            isPerformingOperation = false,
                            operationResult =
                                if (data.success) {
                                    OperationResult.Success(data.message)
                                } else {
                                    OperationResult.Failure(data.message)
                                },
                        )
                    }
                }.onError { uiError ->
                    if (!promptUnlockIfLocked(uiError)) {
                        _uiState.update {
                            it.copy(
                                isPerformingOperation = false,
                                operationResult = OperationResult.Failure(uiError.toString()),
                            )
                        }
                    }
                }
        }
    }

    /**
     * Check TSE health.
     */
    fun checkHealth() {
        viewModelScope.launch {
            _uiState.update { it.copy(isPerformingOperation = true, operationResult = null) }

            runCatchingApi { tseSettingsApi.getHealth() }
                .onSuccess { data ->
                    _uiState.update {
                        it.copy(
                            healthCheck = data,
                            isPerformingOperation = false,
                            operationResult =
                                OperationResult.Success(
                                    if (data.healthy) "TSE is healthy" else "TSE health check failed",
                                ),
                        )
                    }
                }.onError { uiError ->
                    if (!promptUnlockIfLocked(uiError)) {
                        _uiState.update {
                            it.copy(
                                isPerformingOperation = false,
                                operationResult = OperationResult.Failure(uiError.toString()),
                            )
                        }
                    }
                }
        }
    }

    /**
     * Check TSE flash memory health.
     */
    fun checkFlashHealth() {
        viewModelScope.launch {
            _uiState.update { it.copy(isPerformingOperation = true, operationResult = null) }

            runCatchingApi { tseSettingsApi.getFlashHealth() }
                .onSuccess { data ->
                    _uiState.update {
                        it.copy(
                            flashHealth = data,
                            isPerformingOperation = false,
                            operationResult =
                                if (data.needsReplacement) {
                                    OperationResult.Failure("TSE needs replacement!")
                                } else {
                                    OperationResult.Success(data.message)
                                },
                        )
                    }
                }.onError { uiError ->
                    if (!promptUnlockIfLocked(uiError)) {
                        _uiState.update {
                            it.copy(
                                isPerformingOperation = false,
                                operationResult = OperationResult.Failure(uiError.toString()),
                            )
                        }
                    }
                }
        }
    }

    /**
     * Check for available TSE firmware updates.
     */
    fun checkFirmwareUpdate() {
        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingFirmware = true, operationResult = null) }

            runCatchingApi { tseSettingsApi.checkFirmwareUpdate() }
                .onSuccess { data ->
                    _uiState.update {
                        it.copy(
                            firmwareUpdateCheck = data,
                            isCheckingFirmware = false,
                            operationResult = OperationResult.Success(data.message),
                        )
                    }
                }.onError { uiError ->
                    _uiState.update {
                        it.copy(
                            isCheckingFirmware = false,
                            operationResult = OperationResult.Failure(uiError.toString()),
                        )
                    }
                }
        }
    }

    /**
     * Initialize/setup TSE hardware.
     * Should only be called when TSE is not yet initialized.
     */
    fun showSetupDialog() {
        _uiState.update { it.copy(showSetupDialog = true, setupError = null) }
        loadSetupTerminals()
    }

    /** Chooses the terminal the setup registers as the TSE's first client; only a listed one is taken. */
    fun selectSetupTerminal(clientId: String) {
        if (_uiState.value.setupTerminals.none { it.clientId == clientId }) return
        _uiState.update { it.copy(selectedSetupTerminalId = clientId, setupError = null) }
    }

    /**
     * Lists the active fiscal terminals for the setup dialog and preselects the caller's own
     * terminal when it is one — a till commissions with itself, as before — else the first. The
     * back office is no terminal, so its own lookup fails and the first terminal is chosen. A failed
     * listing is shown in the dialog rather than read as "no terminal exists".
     */
    private fun loadSetupTerminals() {
        _uiState.update { it.copy(isLoadingSetupTerminals = true, setupTerminals = emptyList(), selectedSetupTerminalId = null) }
        viewModelScope.launch {
            runCatchingApi { terminalApi.getAllTerminals() }
                .onSuccess { terminals ->
                    val options = TseSetupTerminalOption.eligible(terminals)
                    val ownClientId =
                        if (options.isEmpty()) null else runCatchingApi { terminalApi.getCurrentTerminal() }.getOrNull()?.terminalId
                    _uiState.update {
                        it.copy(
                            isLoadingSetupTerminals = false,
                            setupTerminals = options,
                            selectedSetupTerminalId = TseSetupTerminalOption.preselect(options, ownClientId),
                        )
                    }
                }.onError { uiError ->
                    _uiState.update { it.copy(isLoadingSetupTerminals = false, setupError = uiError) }
                }
        }
    }

    fun hideSetupDialog() {
        _uiState.update { it.copy(showSetupDialog = false, setupError = null) }
    }

    /**
     * Commissions the TSE: saves the configuration (which carries [timeAdminPin]), then sends the
     * one-time credentials.
     *
     * Setup writes the TimeAdmin PIN to the device together with the Admin PIN, so it is collected in
     * the same dialog and refused here when blank or a factory placeholder — the dialog disables its
     * button for the same reason, and the server refuses both too. Any failure ends the spinner and is
     * shown in the dialog ([TseSettingsUiState.setupError]), which stays open: a refused config save
     * used to leave "TSE operation in progress" spinning forever with setup never sent (found on a
     * real Swissbit TSE).
     */
    fun setupTse(
        adminPin: String,
        adminPuk: String,
        credentialSeed: String,
        timeAdminPin: String,
    ) {
        if (_uiState.value.isPerformingOperation) return
        val terminalId = _uiState.value.selectedSetupTerminalId
        val invalidPinKey =
            when {
                // The dialog disables its button without one; the server refuses it too.
                terminalId == null -> TseSettingsKeys.setupNoTerminal
                // The config save below writes the form's mode, and the server refuses a setup in
                // SIMULATION (an operator's deliberate choice it does not end on its own). DISABLED,
                // the fresh-install value, needs no change: setup commissions in NORMAL and saves it.
                _uiState.value.formState.mode == TseMode.SIMULATION.name -> TseSettingsKeys.setupSimulationMode
                timeAdminPin.isBlank() -> TseSettingsKeys.timeAdminPinRequired
                TseCredentialPlaceholders.isPlaceholderPin(timeAdminPin) -> TseSettingsKeys.placeholderCredential
                else -> null
            }
        if (invalidPinKey != null) {
            _uiState.update { it.copy(setupError = setupHint(invalidPinKey)) }
            return
        }
        _uiState.update {
            it.copy(
                isPerformingOperation = true,
                operationResult = null,
                setupError = null,
                formState = it.formState.copy(timeAdminPin = timeAdminPin),
            )
        }

        // Credentials come from the setup dialog, not stored config, and travel exactly
        // once: the server keeps none of them and the seed is consumed by this call.
        saveConfig(onFailure = ::failSetup, surfaceApplyResult = false) {
            runCatchingApi {
                tseSettingsApi.setup(
                    TseDto.SetupCredentialsRequestDto(
                        adminPin = adminPin,
                        adminPuk = adminPuk,
                        credentialSeed = credentialSeed,
                        terminalId = terminalId,
                    ),
                )
            }.onSuccess { data ->
                _uiState.update {
                    it.copy(
                        isPerformingOperation = false,
                        showSetupDialog = !data.success,
                        operationResult =
                            if (data.success) {
                                OperationResult.Success(data.message)
                            } else {
                                OperationResult.Failure(data.message)
                            },
                    )
                }
                // A successful setup saves the mode as NORMAL on the server. Reload it, or the form
                // keeps the DISABLED the pre-setup save returned and a later save writes it back —
                // and the signing guard refuses a NORMAL device whose configured mode is DISABLED.
                // Re-ping too: a TSE commissioned from DISABLED did not answer before setup opened it,
                // and without a fresh ping the screen would go on reporting it as unreachable.
                if (data.success) {
                    viewModelScope.launch {
                        loadConfig()
                        checkTseAvailability()
                    }
                } else {
                    refreshStatus()
                }
            }.onError { uiError -> failSetup(uiError) }
        }
    }

    /** Ends the setup spinner and shows [uiError] in the setup dialog, unless it asks for an admin sitting. */
    private fun failSetup(uiError: UiError) {
        if (promptUnlockIfLocked(uiError)) return
        _uiState.update { it.copy(isPerformingOperation = false, setupError = uiError.forSetupDialog()) }
    }

    /** A server refusal naming the TimeAdmin PIN reads as the dialog's own hint, in the operator's language. */
    private fun UiError.forSetupDialog(): UiError =
        if (this is UiError.FromServer && fieldErrors.any { it.field == TIME_ADMIN_PIN_FIELD }) {
            copy(titleKey = TseSettingsKeys.setupDialogTitle, messageKey = TseSettingsKeys.timeAdminPinRequired)
        } else {
            this
        }

    private fun setupHint(messageKey: StringKey): UiError = UiError.Unknown(titleKey = TseSettingsKeys.setupDialogTitle, messageKey = messageKey)

    /**
     * Restart TSE connection.
     *
     * Resets mode to NORMAL, resumes retry service, and performs health check.
     * Will broadcast updated TSE status to all connected clients.
     */
    fun restartTse() {
        viewModelScope.launch {
            _uiState.update { it.copy(isPerformingOperation = true, operationResult = null) }

            runCatchingApi { tseSettingsApi.restartTse() }
                .onSuccess { data ->
                    _uiState.update {
                        it.copy(
                            isPerformingOperation = false,
                            operationResult =
                                if (data.success) {
                                    OperationResult.Success(data.message)
                                } else {
                                    OperationResult.Failure(data.message)
                                },
                        )
                    }
                    loadTseData()
                }.onError { uiError ->
                    if (!promptUnlockIfLocked(uiError)) {
                        _uiState.update {
                            it.copy(
                                isPerformingOperation = false,
                                operationResult = OperationResult.Failure(uiError.toString()),
                            )
                        }
                    }
                }
        }
    }

    /**
     * Factory reset TSE (development firmware only).
     * WARNING: This is a destructive operation.
     */
    fun factoryReset() {
        viewModelScope.launch {
            _uiState.update { it.copy(isPerformingOperation = true, operationResult = null) }

            runCatchingApi { tseSettingsApi.factoryReset() }
                .onSuccess { data ->
                    _uiState.update {
                        it.copy(
                            isPerformingOperation = false,
                            operationResult =
                                if (data.success) {
                                    OperationResult.Success(data.message)
                                } else {
                                    OperationResult.Failure(data.message)
                                },
                        )
                    }
                    refreshStatus()
                }.onError { uiError ->
                    if (!promptUnlockIfLocked(uiError)) {
                        _uiState.update {
                            it.copy(
                                isPerformingOperation = false,
                                operationResult = OperationResult.Failure(uiError.toString()),
                            )
                        }
                    }
                }
        }
    }

    // ========================================
    // Decommission Operations (Phase 2)
    // ========================================

    // ============================================================================
    // TSE admin session
    //
    // The Admin PIN is not stored anywhere, so administrative actions need an operator to
    // supply it. Rather than prompting up front for actions that may not need it, the failure
    // itself opens the prompt: any call refused with TSE_ADMIN_LOCKED raises the dialog.
    // ============================================================================

    /** Raises the unlock prompt when an operation was refused for want of an admin sitting. */
    private fun promptUnlockIfLocked(uiError: UiError): Boolean {
        if (uiError.code != PosErrorCode.TSE_ADMIN_LOCKED) return false
        _uiState.update {
            it.copy(
                isAdminUnlocked = false,
                showAdminUnlockDialog = true,
                adminUnlockPin = "",
                isPerformingOperation = false,
            )
        }
        return true
    }

    fun showAdminUnlockDialog() {
        _uiState.update { it.copy(showAdminUnlockDialog = true, adminUnlockPin = "") }
    }

    fun hideAdminUnlockDialog() {
        _uiState.update { it.copy(showAdminUnlockDialog = false, adminUnlockPin = "") }
    }

    fun updateAdminUnlockPin(pin: String) {
        _uiState.update { it.copy(adminUnlockPin = pin) }
    }

    fun unlockAdmin() {
        val pin = _uiState.value.adminUnlockPin
        if (pin.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isUnlockingAdmin = true) }
            runCatchingApi { tseSettingsApi.unlockAdmin(pin) }
                .onSuccess { status ->
                    // The PIN leaves the client here and is not retained: it lives only in the
                    // server's in-memory session for the length of the sitting.
                    _uiState.update {
                        it.copy(
                            isUnlockingAdmin = false,
                            isAdminUnlocked = status.unlocked,
                            showAdminUnlockDialog = false,
                            adminUnlockPin = "",
                        )
                    }
                }.onError { uiError ->
                    _uiState.update {
                        it.copy(
                            isUnlockingAdmin = false,
                            adminUnlockPin = "",
                            operationResult = OperationResult.Failure(uiError.toString()),
                        )
                    }
                }
        }
    }

    fun lockAdmin() {
        viewModelScope.launch {
            runCatchingApi { tseSettingsApi.lockAdmin() }
                .onSuccess { status -> _uiState.update { it.copy(isAdminUnlocked = status.unlocked) } }
        }
    }

    /** Refreshes whether a sitting is live, so the UI does not claim one that has timed out. */
    fun refreshAdminSessionStatus() {
        viewModelScope.launch {
            runCatchingApi { tseSettingsApi.adminSessionStatus() }
                .onSuccess { status -> _uiState.update { it.copy(isAdminUnlocked = status.unlocked) } }
        }
    }

    /**
     * Show the decommission confirmation dialog.
     */
    fun showDecommissionDialog() {
        _uiState.update { it.copy(showDecommissionDialog = true, decommissionConfirmText = "") }
    }

    /**
     * Hide the decommission confirmation dialog.
     */
    fun hideDecommissionDialog() {
        _uiState.update { it.copy(showDecommissionDialog = false, decommissionConfirmText = "") }
    }

    /**
     * Update the decommission confirmation text.
     */
    fun updateDecommissionConfirmText(text: String) {
        _uiState.update { it.copy(decommissionConfirmText = text) }
    }

    /**
     * Decommission the TSE.
     * WARNING: IRREVERSIBLE operation.
     */
    fun decommission() {
        viewModelScope.launch {
            _uiState.update { it.copy(isPerformingOperation = true, operationResult = null, showDecommissionDialog = false) }

            runCatchingApi { tseSettingsApi.decommission() }
                .onSuccess { data ->
                    _uiState.update {
                        it.copy(
                            isPerformingOperation = false,
                            operationResult =
                                if (data.success) {
                                    OperationResult.Success(data.message)
                                } else {
                                    OperationResult.Failure(data.message)
                                },
                        )
                    }
                    refreshStatus()
                }.onError { uiError ->
                    if (!promptUnlockIfLocked(uiError)) {
                        _uiState.update {
                            it.copy(
                                isPerformingOperation = false,
                                operationResult = OperationResult.Failure(uiError.toString()),
                            )
                        }
                    }
                }
        }
    }

    // ========================================
    // Firmware Apply Operations (Phase 3)
    // ========================================

    /**
     * Show the firmware apply confirmation dialog.
     */
    fun showFirmwareApplyDialog() {
        _uiState.update { it.copy(showFirmwareApplyDialog = true) }
    }

    /**
     * Hide the firmware apply confirmation dialog.
     */
    fun hideFirmwareApplyDialog() {
        _uiState.update { it.copy(showFirmwareApplyDialog = false) }
    }

    /**
     * Apply bundled firmware update.
     * WARNING: Long-running operation.
     */
    fun applyBundledFirmwareUpdate() {
        viewModelScope.launch {
            _uiState.update { it.copy(isPerformingOperation = true, operationResult = null, showFirmwareApplyDialog = false) }

            runCatchingApi { tseSettingsApi.applyBundledFirmwareUpdate() }
                .onSuccess { data ->
                    _uiState.update {
                        it.copy(
                            isPerformingOperation = false,
                            firmwareApplyResult = data,
                            operationResult =
                                if (data.success) {
                                    OperationResult.Success(data.message)
                                } else {
                                    OperationResult.Failure(data.message)
                                },
                        )
                    }
                    refreshStatus()
                }.onError { uiError ->
                    if (!promptUnlockIfLocked(uiError)) {
                        _uiState.update {
                            it.copy(
                                isPerformingOperation = false,
                                operationResult = OperationResult.Failure(uiError.toString()),
                            )
                        }
                    }
                }
        }
    }

    // ========================================
    // CSP Export Operations (Phase 4)
    // ========================================

    /**
     * Disable export if CSP self-test fails.
     */
    fun disableExportIfCspTestFails() {
        viewModelScope.launch {
            _uiState.update { it.copy(isPerformingOperation = true, operationResult = null) }

            runCatchingApi { tseSettingsApi.disableExportIfCspTestFails() }
                .onSuccess { data ->
                    _uiState.update {
                        it.copy(
                            isPerformingOperation = false,
                            operationResult =
                                if (data.success) {
                                    OperationResult.Success(data.message)
                                } else {
                                    OperationResult.Failure(data.message)
                                },
                        )
                    }
                }.onError { uiError ->
                    if (!promptUnlockIfLocked(uiError)) {
                        _uiState.update {
                            it.copy(
                                isPerformingOperation = false,
                                operationResult = OperationResult.Failure(uiError.toString()),
                            )
                        }
                    }
                }
        }
    }

    // ========================================
    // Log Entry Browsing (Phase 5)
    // ========================================

    /**
     * Toggle log entry section expansion.
     */
    fun toggleLogEntrySection() {
        val newExpanded = !_uiState.value.isLogEntrySectionExpanded
        _uiState.update { it.copy(isLogEntrySectionExpanded = newExpanded) }

        if (newExpanded && _uiState.value.logEntries == null) {
            browseEntries("first")
        }
    }

    /**
     * Browse log entries with specified direction.
     */
    fun browseEntries(
        direction: String,
        entryId: Long? = null,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingEntries = true) }

            val request =
                TseDto.LogEntryBrowseRequestDto(
                    direction = direction,
                    entryId = entryId,
                    count = 20,
                )

            runCatchingApi { tseSettingsApi.browseEntries(request) }
                .onSuccess { entries ->
                    _uiState.update {
                        it.copy(logEntries = entries, isLoadingEntries = false)
                    }
                }.onError { _ ->
                    _uiState.update { it.copy(isLoadingEntries = false) }
                }
        }
    }

    /**
     * Export the loaded TSE log entries as JSON for offline analysis.
     *
     * The card can only show the raw hex because the TR-03153 parser currently decodes little
     * of the ASN.1 DER. This dumps each entry's raw log-message and process-data hex — plus the
     * device context (generation, software/SDK version, log-time format) that the encoding
     * depends on, and the best-effort parse for comparison — so the bytes can be decoded
     * properly away from the device.
     */
    fun exportLogEntriesForAnalysis() {
        val loaded = _uiState.value.logEntries
        if (loaded == null || loaded.entries.isEmpty()) return
        val info = _uiState.value.tseInfo
        val status = _uiState.value.status
        viewModelScope.launch {
            val json =
                Json {
                    prettyPrint = true
                    encodeDefaults = true
                }
            val root =
                buildJsonObject {
                    put(
                        "purpose",
                        "TR-03153 TSE log-message analysis: raw ASN.1 DER hex per entry, with the best-effort parse for comparison",
                    )
                    put("tseSerialNumberHex", info?.tseSerialNumberHex ?: "")
                    put("isTseV2", info?.isTseV2 ?: false)
                    put("softwareVersion", info?.softwareVersion ?: 0)
                    put("hardwareVersion", info?.hardwareVersion ?: 0)
                    put("sdkVersion", status?.sdkVersion ?: "")
                    put("logTimeFormat", status?.logTimeFormat ?: "")
                    put("entryCount", loaded.entries.size)
                    put(
                        "entries",
                        json.encodeToJsonElement(
                            ListSerializer(TseDto.LogEntryDto.serializer()),
                            loaded.entries,
                        ),
                    )
                }
            val bytes = json.encodeToString(JsonObject.serializer(), root).encodeToByteArray()
            FileSaver.saveFile(
                data = bytes,
                filename = "tse_log_analysis.json",
                mimeType = "application/json",
                onSuccess = { path ->
                    _uiState.update { it.copy(successMessage = "TSE log exported for analysis: $path") }
                },
                onError = {
                    _uiState.update { it.copy(operationResult = OperationResult.Failure("TSE log export failed")) }
                },
            )
        }
    }

    // ========================================
    // LCM Export (Phase 6)
    // ========================================

    /**
     * Export LCM diagnostics data from TSE.
     */
    fun exportLcm() {
        viewModelScope.launch {
            _uiState.update { it.copy(isPerformingOperation = true, operationResult = null) }

            runCatchingApi { tseSettingsApi.exportLcm() }
                .onSuccess { data ->
                    _uiState.update {
                        it.copy(
                            isPerformingOperation = false,
                            lcmExportResult = data,
                            operationResult =
                                if (data.success) {
                                    OperationResult.Success(data.message)
                                } else {
                                    OperationResult.Failure(data.message)
                                },
                        )
                    }
                }.onError { uiError ->
                    if (!promptUnlockIfLocked(uiError)) {
                        _uiState.update {
                            it.copy(
                                isPerformingOperation = false,
                                operationResult = OperationResult.Failure(uiError.toString()),
                            )
                        }
                    }
                }
        }
    }

    // ========================================
    // Clients & Transactions
    // ========================================

    /**
     * Load registered TSE clients.
     */
    fun loadClients() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingClients = true) }

            runCatchingApi { tseSettingsApi.getClients() }
                .onSuccess { resp ->
                    _uiState.update {
                        it.copy(clients = resp.clients, isLoadingClients = false)
                    }
                }.onError { uiError ->
                    _uiState.update { it.copy(isLoadingClients = false) }
                    // These fire when a collapsible section expands, so a locked TSE would
                    // otherwise fail silently. Raise the unlock prompt like every other admin op.
                    promptUnlockIfLocked(uiError)
                }
        }
    }

    /**
     * Load open transactions.
     */
    fun loadOpenTransactions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingTransactions = true) }

            runCatchingApi { tseSettingsApi.getTransactions() }
                .onSuccess { resp ->
                    _uiState.update {
                        it.copy(openTransactions = resp.transactionNumbers, isLoadingTransactions = false)
                    }
                }.onError { uiError ->
                    _uiState.update { it.copy(isLoadingTransactions = false) }
                    // These fire when a collapsible section expands, so a locked TSE would
                    // otherwise fail silently. Raise the unlock prompt like every other admin op.
                    promptUnlockIfLocked(uiError)
                }
        }
    }

    /**
     * Load last transaction response.
     */
    fun loadLastTransaction() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingLastTransaction = true) }

            runCatchingApi { tseSettingsApi.getLastTransaction() }
                .onSuccess { resp ->
                    _uiState.update {
                        it.copy(lastTransaction = resp, isLoadingLastTransaction = false)
                    }
                }.onError { uiError ->
                    _uiState.update { it.copy(isLoadingLastTransaction = false) }
                    // These fire when a collapsible section expands, so a locked TSE would
                    // otherwise fail silently. Raise the unlock prompt like every other admin op.
                    promptUnlockIfLocked(uiError)
                }
        }
    }

    // ========================================
    // Utility
    // ========================================

    /**
     * Clear operation result message.
     */
    fun clearOperationResult() {
        _uiState.update { it.copy(operationResult = null) }
    }

    /**
     * Clear error message.
     */
    // ============================================================================
    // DSFinV-K + TSE TAR export (moved here from the DATEV export menu — these are
    // TSE operations). State classes live in the shared settings.state package.
    // ============================================================================

    fun toggleDsfinvkSection() {
        _uiState.update { it.copy(dsfinvkState = it.dsfinvkState.copy(isExpanded = !it.dsfinvkState.isExpanded)) }
    }

    fun updateDsfinvkDateRange(
        startDate: String,
        endDate: String,
    ) {
        _uiState.update { state ->
            state.copy(
                dsfinvkState =
                    state.dsfinvkState.copy(
                        startDate = startDate,
                        endDate = endDate,
                        validationResult = null,
                        statistics = null,
                    ),
            )
        }
    }

    fun toggleIncludeTseTar() {
        _uiState.update { it.copy(dsfinvkState = it.dsfinvkState.copy(includeTseTar = !it.dsfinvkState.includeTseTar)) }
    }

    fun validateDsfinvk() {
        val current = _uiState.value.dsfinvkState
        if (!current.hasValidDateRange()) return
        viewModelScope.launch {
            _uiState.update { it.copy(dsfinvkState = it.dsfinvkState.copy(isLoadingValidation = true)) }
            runCatchingApi { dsfinvkApiClient.validate(current.startDate, current.endDate) }
                .onSuccess { data ->
                    _uiState.update { it.copy(dsfinvkState = it.dsfinvkState.copy(validationResult = data, isLoadingValidation = false)) }
                }.onError { uiError ->
                    _uiState.update { it.copy(error = uiError, dsfinvkState = it.dsfinvkState.copy(isLoadingValidation = false)) }
                }
        }
    }

    fun loadDsfinvkStatistics() {
        val current = _uiState.value.dsfinvkState
        if (!current.hasValidDateRange()) return
        viewModelScope.launch {
            _uiState.update { it.copy(dsfinvkState = it.dsfinvkState.copy(isLoadingStatistics = true)) }
            runCatchingApi { dsfinvkApiClient.getStatistics(current.startDate, current.endDate) }
                .onSuccess { data ->
                    _uiState.update { it.copy(dsfinvkState = it.dsfinvkState.copy(statistics = data, isLoadingStatistics = false)) }
                }.onError { uiError ->
                    _uiState.update { it.copy(error = uiError, dsfinvkState = it.dsfinvkState.copy(isLoadingStatistics = false)) }
                }
        }
    }

    fun exportDsfinvk() {
        val current = _uiState.value.dsfinvkState
        if (!current.canExport()) return
        viewModelScope.launch {
            _uiState.update { it.copy(dsfinvkState = it.dsfinvkState.copy(isExporting = true)) }
            val request =
                DsfinvkExportDto.ExportRequestDto(
                    startDate = current.startDate,
                    endDate = current.endDate,
                    includeTseTar = current.includeTseTar,
                )
            runCatchingApi { dsfinvkApiClient.exportZip(request) }
                .onSuccess { data ->
                    val filename = "dsfinvk_${current.startDate}_${current.endDate}.zip"
                    FileSaver.saveFile(
                        data = data,
                        filename = filename,
                        mimeType = "application/zip",
                        onSuccess = { path ->
                            _uiState.update {
                                it.copy(
                                    successMessage = "DSFinV-K: $path",
                                    dsfinvkState = it.dsfinvkState.copy(isExporting = false),
                                )
                            }
                        },
                        onError = {
                            _uiState.update { it.copy(dsfinvkState = it.dsfinvkState.copy(isExporting = false)) }
                        },
                    )
                }.onError { uiError ->
                    _uiState.update { it.copy(error = uiError, dsfinvkState = it.dsfinvkState.copy(isExporting = false)) }
                }
        }
    }

    fun toggleTseExportSection() {
        _uiState.update { it.copy(tseExportState = it.tseExportState.copy(isExpanded = !it.tseExportState.isExpanded)) }
    }

    fun updateTseExportType(type: TseExportType) {
        _uiState.update { it.copy(tseExportState = it.tseExportState.copy(selectedExportType = type, exportResult = null)) }
        if (type == TseExportType.TIME_FILTERED) loadTseExportClients()
    }

    /**
     * Loads the registers the time-filtered export's "Kasse" selector offers. "All registers" stays
     * the default and needs no list, so a failure here only leaves the selector at that default —
     * it is not an error worth interrupting the export for.
     */
    fun loadTseExportClients() {
        viewModelScope.launch {
            runCatchingApi { terminalApi.getAllTerminals() }
                .onSuccess { terminals ->
                    val options = TseSetupTerminalOption.exportable(terminals)
                    _uiState.update { state ->
                        val selected = state.tseExportState.selectedClientId?.takeIf { id -> options.any { it.clientId == id } }
                        state.copy(tseExportState = state.tseExportState.copy(clientOptions = options, selectedClientId = selected))
                    }
                }
        }
    }

    /** Selects the register to export; null = all registers. */
    fun selectTseExportClient(clientId: String?) {
        _uiState.update { it.copy(tseExportState = it.tseExportState.copy(selectedClientId = clientId)) }
    }

    fun updateTseExportDateRange(
        startDate: String,
        endDate: String,
    ) {
        _uiState.update { it.copy(tseExportState = it.tseExportState.copy(startDate = startDate, endDate = endDate)) }
    }

    fun loadTseExportSize() {
        viewModelScope.launch {
            _uiState.update { it.copy(tseExportState = it.tseExportState.copy(isLoadingSize = true)) }
            runCatchingApi { tseSettingsApi.getExportSize() }
                .onSuccess { size ->
                    _uiState.update { it.copy(tseExportState = it.tseExportState.copy(exportSize = size, isLoadingSize = false)) }
                }.onError { uiError ->
                    _uiState.update { it.copy(error = uiError, tseExportState = it.tseExportState.copy(isLoadingSize = false)) }
                }
        }
    }

    fun exportTse() {
        val current = _uiState.value.tseExportState
        if (!current.canExport()) return
        viewModelScope.launch {
            _uiState.update { it.copy(tseExportState = it.tseExportState.copy(isExporting = true, exportResult = null)) }
            val result =
                when (current.selectedExportType) {
                    TseExportType.FULL -> runCatchingApi { tseSettingsApi.exportFull() }
                    TseExportType.INCREMENTAL -> runCatchingApi { tseSettingsApi.exportIncremental(TseDto.IncrementalExportRequestDto()) }
                    TseExportType.TIME_FILTERED -> {
                        // Days, not instants: the server resolves them in the shop's time zone
                        // and includes the whole end day (T5-6). Converting here used the
                        // device's zone and cut the period at the end day's midnight.
                        val startDay = parseIsoDay(current.startDate)
                        val endDay = parseIsoDay(current.endDate)
                        if (startDay == null || endDay == null) {
                            _uiState.update {
                                it.copy(
                                    error =
                                        UiError.Unknown(
                                            titleKey = CommonKeys.unknownErrorTitle,
                                            messageKey = CommonKeys.unknownErrorMessage,
                                        ),
                                    tseExportState = it.tseExportState.copy(isExporting = false),
                                )
                            }
                            return@launch
                        }
                        runCatchingApi {
                            tseSettingsApi.exportTimeFiltered(
                                TseDto.TimeFilteredExportRequestDto(
                                    startDay = startDay.toString(),
                                    endDay = endDay.toString(),
                                    clientId = current.selectedClientId,
                                ),
                            )
                        }
                    }
                }
            result
                .onSuccess { resp ->
                    _uiState.update {
                        it.copy(
                            successMessage = resp.message,
                            tseExportState = it.tseExportState.copy(exportResult = resp, isExporting = false),
                        )
                    }
                }.onError { uiError ->
                    _uiState.update { it.copy(error = uiError, tseExportState = it.tseExportState.copy(isExporting = false)) }
                }
        }
    }

    // ============================================================================
    // Export log-sequence plausibility verification (AGD §5.2 / OE.Transaction)
    // ============================================================================

    /**
     * Verifies a previously exported TAR: gap-free transaction numbers, unique signature
     * counters, and plausible timestamps. [fileName] must resolve inside the server's TSE
     * exports directory — the server re-validates regardless of what the UI sends.
     */
    fun verifyExport(fileName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isVerifyingExport = true, verificationReport = null) }
            runCatchingApi { tseSettingsApi.verifyExport(fileName) }
                .onSuccess { report ->
                    _uiState.update { it.copy(isVerifyingExport = false, verificationReport = report) }
                }.onError { uiError ->
                    _uiState.update { it.copy(isVerifyingExport = false, error = uiError) }
                }
        }
    }

    fun requestDeleteExportedData() {
        _uiState.update { it.copy(tseExportState = it.tseExportState.copy(showDeleteConfirmation = true)) }
    }

    fun cancelDeleteExportedData() {
        _uiState.update { it.copy(tseExportState = it.tseExportState.copy(showDeleteConfirmation = false)) }
    }

    fun confirmDeleteExportedData() {
        viewModelScope.launch {
            _uiState.update { it.copy(tseExportState = it.tseExportState.copy(isDeleting = true, showDeleteConfirmation = false)) }
            runCatchingApi { tseSettingsApi.deleteExportedData() }
                .onSuccess { resp ->
                    _uiState.update {
                        it.copy(
                            successMessage = resp.message,
                            tseExportState =
                                it.tseExportState.copy(
                                    isDeleting = false,
                                    exportResult = null,
                                    exportSize = null,
                                    // An archive the server could not register is offered for "Archiv registrieren".
                                    archiveFileName = if (resp.archiveRegistered) it.tseExportState.archiveFileName else resp.archiveFileName,
                                    unregisteredArchiveFileName = resp.archiveFileName.takeUnless { resp.archiveRegistered },
                                ),
                        )
                    }
                }.onError { uiError ->
                    _uiState.update { it.copy(error = uiError, tseExportState = it.tseExportState.copy(isDeleting = false)) }
                }
        }
    }

    fun updateArchiveFileName(fileName: String) {
        _uiState.update { it.copy(tseExportState = it.tseExportState.copy(archiveFileName = fileName)) }
    }

    /**
     * Enters an archive already in the server's TSE export folder in the archive register, so the
     * DSFinV-K export can cover a period erased from the TSE with it (real-TSE QA rt14).
     */
    fun registerExportArchive() {
        val current = _uiState.value.tseExportState
        if (!current.canRegisterArchive()) return
        val fileName = current.archiveFileName.trim()
        viewModelScope.launch {
            _uiState.update { it.copy(tseExportState = it.tseExportState.copy(isRegisteringArchive = true, registeredArchive = null)) }
            runCatchingApi { tseSettingsApi.registerExportArchive(fileName) }
                .onSuccess { archive ->
                    _uiState.update {
                        it.copy(
                            tseExportState =
                                it.tseExportState.copy(
                                    isRegisteringArchive = false,
                                    registeredArchive = archive,
                                    archiveFileName = "",
                                    unregisteredArchiveFileName =
                                        it.tseExportState.unregisteredArchiveFileName?.takeUnless { name -> name == archive.fileName },
                                ),
                        )
                    }
                }.onError { uiError ->
                    _uiState.update { it.copy(error = uiError, tseExportState = it.tseExportState.copy(isRegisteringArchive = false)) }
                }
        }
    }

    fun clearSuccessMessage() {
        _uiState.update { it.copy(successMessage = null) }
    }

    @OptIn(ExperimentalTime::class)
    private fun initializeDsfinvkDates() {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val today = "${now.year}-${now.month.number.toString().padStart(2, '0')}-${now.day.toString().padStart(2, '0')}"
        val firstOfMonth = "${now.year}-${now.month.number.toString().padStart(2, '0')}-01"
        _uiState.update { it.copy(dsfinvkState = it.dsfinvkState.copy(startDate = firstOfMonth, endDate = today)) }
    }

    private fun parseIsoDay(isoDate: String): LocalDate? {
        return try {
            val parts = isoDate.split("-")
            if (parts.size != 3) return null
            LocalDate(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        } catch (e: Exception) {
            null
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Clear save success message.
     */
    fun clearSaveSuccess() {
        _uiState.update { it.copy(saveSuccess = false) }
    }

    /** Clear the last config-apply result after it has been surfaced to the operator. */
    fun clearApplyResult() {
        _uiState.update { it.copy(applyResult = null) }
    }

    private companion object {
        /** The field the server names when it refuses a config without a TimeAdmin PIN. */
        const val TIME_ADMIN_PIN_FIELD = "timeAdminPin"

        /** Apply statuses that represent a config that was actually applied operationally. */
        val OPERATIONAL_APPLY_STATUSES =
            setOf(
                TseConfigDto.TseModeApplyStatus.OPERATIONAL,
                TseConfigDto.TseModeApplyStatus.DISABLED,
            )
    }

    // ========================================
    // Queue Monitoring
    // ========================================

    /**
     * Load queue status summary.
     */
    fun loadQueueStatus() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingQueueStatus = true) }

            runCatchingApi { tseQueueApi.getQueueStatus() }
                .onSuccess { resp ->
                    _uiState.update {
                        it.copy(queueStatus = resp, isLoadingQueueStatus = false)
                    }
                }.onError { _ ->
                    _uiState.update { it.copy(isLoadingQueueStatus = false) }
                }
        }
    }

    /**
     * Load queue entries with optional status filter.
     */
    fun loadQueueEntries(status: TseSigningStatus? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingQueueEntries = true, queueEntriesFilter = status) }

            runCatchingApi { tseQueueApi.getQueueEntries(status = status) }
                .onSuccess { resp ->
                    _uiState.update {
                        it.copy(queueEntries = resp, isLoadingQueueEntries = false)
                    }
                }.onError { _ ->
                    _uiState.update { it.copy(isLoadingQueueEntries = false) }
                }
        }
    }

    /**
     * Toggle queue section expansion and load data if expanding.
     */
    fun toggleQueueSection() {
        val newExpanded = !_uiState.value.isQueueSectionExpanded
        _uiState.update { it.copy(isQueueSectionExpanded = newExpanded) }

        if (newExpanded && _uiState.value.queueStatus == null) {
            loadQueueStatus()
            loadQueueEntries()
        }
    }

    /**
     * Refresh queue status and entries.
     */
    fun refreshQueueData() {
        loadQueueStatus()
        if (_uiState.value.isQueueSectionExpanded) {
            loadQueueEntries(_uiState.value.queueEntriesFilter)
        }
    }

    /**
     * Change queue entries filter.
     */
    fun setQueueEntriesFilter(status: TseSigningStatus?) {
        loadQueueEntries(status)
    }

    /**
     * Pause the queue retry service.
     */
    fun pauseQueueService() {
        viewModelScope.launch {
            _uiState.update { it.copy(isPerformingOperation = true, operationResult = null) }

            runCatchingApi { tseQueueApi.pauseService() }
                .onSuccess { data ->
                    _uiState.update {
                        it.copy(
                            isPerformingOperation = false,
                            operationResult =
                                if (data.success) {
                                    OperationResult.Success(data.message)
                                } else {
                                    OperationResult.Failure(data.message)
                                },
                        )
                    }
                    loadQueueStatus()
                }.onError { uiError ->
                    if (!promptUnlockIfLocked(uiError)) {
                        _uiState.update {
                            it.copy(
                                isPerformingOperation = false,
                                operationResult = OperationResult.Failure(uiError.toString()),
                            )
                        }
                    }
                }
        }
    }

    /**
     * Resume the queue retry service.
     */
    fun resumeQueueService() {
        viewModelScope.launch {
            _uiState.update { it.copy(isPerformingOperation = true, operationResult = null) }

            runCatchingApi { tseQueueApi.resumeService() }
                .onSuccess { data ->
                    _uiState.update {
                        it.copy(
                            isPerformingOperation = false,
                            operationResult =
                                if (data.success) {
                                    OperationResult.Success(data.message)
                                } else {
                                    OperationResult.Failure(data.message)
                                },
                        )
                    }
                    loadQueueStatus()
                }.onError { uiError ->
                    if (!promptUnlockIfLocked(uiError)) {
                        _uiState.update {
                            it.copy(
                                isPerformingOperation = false,
                                operationResult = OperationResult.Failure(uiError.toString()),
                            )
                        }
                    }
                }
        }
    }

    /**
     * Trigger manual retry for all pending/queued entries.
     */
    fun triggerManualRetry() {
        viewModelScope.launch {
            _uiState.update { it.copy(isPerformingOperation = true, operationResult = null) }

            runCatchingApi { tseQueueApi.triggerRetry() }
                .onSuccess { data ->
                    _uiState.update {
                        it.copy(
                            isPerformingOperation = false,
                            operationResult =
                                if (data.success) {
                                    OperationResult.Success(
                                        "${data.message} (${data.successCount}/${data.processedCount} successful)",
                                    )
                                } else {
                                    OperationResult.Failure(data.message)
                                },
                        )
                    }
                    refreshQueueData()
                }.onError { uiError ->
                    if (!promptUnlockIfLocked(uiError)) {
                        _uiState.update {
                            it.copy(
                                isPerformingOperation = false,
                                operationResult = OperationResult.Failure(uiError.toString()),
                            )
                        }
                    }
                }
        }
    }

    /**
     * Retry a specific queue entry.
     */
    fun retryEntry(entryId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isPerformingOperation = true, operationResult = null) }

            runCatchingApi { tseQueueApi.retryEntry(entryId) }
                .onSuccess { data ->
                    _uiState.update {
                        it.copy(
                            isPerformingOperation = false,
                            operationResult =
                                if (data.success) {
                                    OperationResult.Success(data.message)
                                } else {
                                    OperationResult.Failure(data.message)
                                },
                        )
                    }
                    refreshQueueData()
                }.onError { uiError ->
                    if (!promptUnlockIfLocked(uiError)) {
                        _uiState.update {
                            it.copy(
                                isPerformingOperation = false,
                                operationResult = OperationResult.Failure(uiError.toString()),
                            )
                        }
                    }
                }
        }
    }

    /**
     * Abandons signing for a queue entry. [reason] is mandatory — the Vorgang stays permanently
     * unsigned, so the server records who abandoned it and why, and rejects a blank reason.
     */
    fun cancelEntry(
        entryId: Long,
        reason: String,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isPerformingOperation = true, operationResult = null) }

            runCatchingApi { tseQueueApi.cancelEntry(entryId, reason) }
                .onSuccess { data ->
                    _uiState.update {
                        it.copy(
                            isPerformingOperation = false,
                            operationResult =
                                if (data.success) {
                                    OperationResult.Success(data.message)
                                } else {
                                    OperationResult.Failure(data.message)
                                },
                        )
                    }
                    refreshQueueData()
                }.onError { uiError ->
                    if (!promptUnlockIfLocked(uiError)) {
                        _uiState.update {
                            it.copy(
                                isPerformingOperation = false,
                                operationResult = OperationResult.Failure(uiError.toString()),
                            )
                        }
                    }
                }
        }
    }

    /**
     * Cleanup old queue entries.
     */
    fun cleanupOldEntries(olderThanDays: Int = 30) {
        viewModelScope.launch {
            _uiState.update { it.copy(isPerformingOperation = true, operationResult = null) }

            runCatchingApi { tseQueueApi.cleanupOldEntries(olderThanDays) }
                .onSuccess { data ->
                    _uiState.update {
                        it.copy(
                            isPerformingOperation = false,
                            operationResult =
                                OperationResult.Success(
                                    "Deleted ${data.deletedCount} entries older than ${data.olderThanDays} days",
                                ),
                        )
                    }
                    refreshQueueData()
                }.onError { uiError ->
                    if (!promptUnlockIfLocked(uiError)) {
                        _uiState.update {
                            it.copy(
                                isPerformingOperation = false,
                                operationResult = OperationResult.Failure(uiError.toString()),
                            )
                        }
                    }
                }
        }
    }
}
