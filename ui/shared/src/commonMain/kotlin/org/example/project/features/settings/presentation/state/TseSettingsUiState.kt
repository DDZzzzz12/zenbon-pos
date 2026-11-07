/**
 * TSE Settings UI State.
 *
 * Represents the complete UI state for the TSE settings tab.
 * Manages configuration, status, admin operations, and client/transaction lists.
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.settings.presentation.state

import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.dto.tse.TseSigningQueueDto
import org.example.project.core.domain.dto.tse.TseVerificationDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.tse.TseFailureBehavior
import org.example.project.core.domain.enums.tse.TseSigningStatus
import org.example.project.core.presentation.error.UiError

/**
 * Main UI state for TSE settings tab.
 *
 * @property isLoading Whether initial data is being loaded
 * @property error Current error if any
 * @property config Current TSE configuration from local storage
 * @property formState Editable form state for configuration
 * @property isEditing Whether in edit mode
 * @property isSaving Whether configuration is being saved
 * @property status TSE status from TSE Connector Server
 * @property tseInfo Detailed TSE information
 * @property healthCheck TSE health check result
 * @property flashHealth TSE flash memory health
 * @property isPerformingOperation Whether an admin operation is in progress
 * @property operationResult Result of last admin operation
 * @property clients List of registered TSE clients
 * @property openTransactions List of unfinished transaction numbers
 * @property isLoadingClients Whether clients list is loading
 * @property isLoadingTransactions Whether transactions list is loading
 */
data class TseSettingsUiState(
    // Loading states
    val isLoading: Boolean = false,
    val error: UiError? = null,
    // Export sections — DSFinV-K and the TSE TAR export live in TSE settings (they are TSE
    // operations), not in the DATEV export menu. The state classes are shared in this package.
    val dsfinvkState: DsfinvkExportState = DsfinvkExportState(),
    val tseExportState: TseExportState = TseExportState(),
    /** One-shot success text for an export/delete, shown as a toast then cleared. */
    val successMessage: String? = null,
    // Configuration (from local DB)
    val config: TseConfigDto.TseConfig? = null,
    val formState: TseConfigFormState = TseConfigFormState(),
    val isEditing: Boolean = false,
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false,
    // Result of the last config/mode apply, surfaced to the operator (null until a save completes).
    // Distinguishes "saved & operational" from "saved but offline / login failed".
    val applyResult: TseConfigDto.TseConfigUpdateResultDto? = null,
    // Status & Info (from TSE Server)
    val status: TseDto.StatusInfoDto? = null,
    val tseInfo: TseDto.TseInfoDto? = null,
    val healthCheck: TseDto.HealthCheckResultDto? = null,
    val flashHealth: TseDto.FlashHealthResultDto? = null,
    val isTseAvailable: Boolean = false,
    /**
     * Whether the server has the Swissbit TSE driver installed.
     *
     * Distinct from [isTseAvailable]: that means "the TSE device answered", this means
     * "this server can drive a local TSE at all". Without the driver the server rejects
     * a switch to LOCAL connection mode, so the UI disables the option.
     *
     * Defaults to true so a server that does not report the flag keeps prior behaviour.
     */
    val isTseSdkAvailable: Boolean = true,
    // Admin Operations
    val isPerformingOperation: Boolean = false,
    val operationResult: OperationResult? = null,
    val firmwareUpdateCheck: TseDto.FirmwareUpdateCheckResultDto? = null,
    val isCheckingFirmware: Boolean = false,
    // Clients & Transactions
    val clients: List<String> = emptyList(),
    val openTransactions: List<Long> = emptyList(),
    val lastTransaction: TseDto.LastTransactionResultDto? = null,
    val isLoadingClients: Boolean = false,
    val isLoadingTransactions: Boolean = false,
    val isLoadingLastTransaction: Boolean = false,
    // Decommission (Phase 2)
    /** True while the server holds an operator-supplied Admin PIN. */
    val isAdminUnlocked: Boolean = false,
    /** Shown when an admin action was refused for want of an unlocked sitting. */
    /** Whether the TSE commissioning dialog is open. */
    val showSetupDialog: Boolean = false,
    /** Why the last commissioning attempt stopped, shown inside the still-open setup dialog. */
    val setupError: UiError? = null,
    /** Active fiscal terminals the setup may register as the TSE's first client; empty until loaded. */
    val setupTerminals: List<TseSetupTerminalOption> = emptyList(),
    /** True while [setupTerminals] is being fetched for the open setup dialog. */
    val isLoadingSetupTerminals: Boolean = false,
    /** Client id of the terminal setup will register; null while none is chosen (button disabled). */
    val selectedSetupTerminalId: String? = null,
    val showAdminUnlockDialog: Boolean = false,
    val adminUnlockPin: String = "",
    val isUnlockingAdmin: Boolean = false,
    val showDecommissionDialog: Boolean = false,
    val decommissionConfirmText: String = "",
    // Firmware Apply (Phase 3)
    val showFirmwareApplyDialog: Boolean = false,
    val firmwareApplyResult: TseDto.FirmwareApplyResultDto? = null,
    // Log Entry Browsing (Phase 5)
    val logEntries: TseDto.LogEntryBrowseResultDto? = null,
    val isLoadingEntries: Boolean = false,
    val isLogEntrySectionExpanded: Boolean = false,
    // LCM Export (Phase 6)
    val lcmExportResult: TseDto.LcmExportResultDto? = null,
    // Queue Monitoring
    val queueStatus: TseSigningQueueDto.QueueStatusDto? = null,
    val queueEntries: List<TseSigningQueueDto.QueueEntryResponseDto> = emptyList(),
    val isLoadingQueueStatus: Boolean = false,
    val isLoadingQueueEntries: Boolean = false,
    val queueEntriesFilter: TseSigningStatus? = null,
    val isQueueSectionExpanded: Boolean = false,
    // Export log-sequence plausibility verification (AGD §5.2 / OE.Transaction)
    val isVerifyingExport: Boolean = false,
    val verificationReport: TseVerificationDto.ReportDto? = null,
) {
    /**
     * Check if form is disabled (loading, saving, or not editing).
     */
    fun isFormDisabled(): Boolean = isLoading || isSaving || !isEditing

    /**
     * Check if save button should be enabled.
     */
    fun canSave(): Boolean = isEditing && !isSaving && formState.hasChanges(config)

    /**
     * Whether the configuration form holds edits that were not saved (what a leave-confirmation asks
     * about). Not editing, or nothing saved to compare against, is never dirty; edit-then-undo is clean.
     */
    fun hasUnsavedConfigEdits(): Boolean = isEditing && config != null && formState.hasChanges(config)

    /**
     * Check if admin operations are available.
     */
    fun canPerformOperations(): Boolean = isTseAvailable && !isPerformingOperation

    /**
     * Check if TSE is initialized (PIN/PUK cannot be changed after initialization).
     */
    fun isTseInitialized(): Boolean = tseInfo?.initializationState == "WORM_INIT_INITIALIZED"

    /**
     * Whether "TSE initialisieren" is offered: the TSE is not known to be commissioned, and either it
     * answers or the shop's stored mode is not NORMAL.
     *
     * DISABLED is the fresh-install value, and in it the server never opens the device, so the ping
     * cannot succeed — the button used to stay hidden on exactly the installs that need it (QA BO-30).
     * Setup opens the TSE itself and commissions it in NORMAL; a TSE that turns out to be commissioned
     * already is refused with 409 before anything is written. SIMULATION is offered too, so the dialog
     * can say why it refuses there (`TseSettingsKeys.setupSimulationMode`) instead of the button
     * silently missing. A NORMAL TSE that does not answer is missing, not uncommissioned: no button.
     */
    fun canOfferSetup(): Boolean {
        val mode = config?.mode
        return !isTseInitialized() && (isTseAvailable || (mode != null && mode != TseMode.NORMAL))
    }
}

/**
 * Form state for TSE configuration editing.
 *
 * Contains string representations of all editable fields.
 */
data class TseConfigFormState(
    val mode: String = "DISABLED",
    val timeAdminPin: String = "",
    val keepAliveEnabled: Boolean = true,
    val keepAliveIntervalSeconds: String = "30",
    val failureBehavior: String = "QUEUE",
) {
    /**
     * Check if form has changes compared to saved config.
     */
    fun hasChanges(config: TseConfigDto.TseConfig?): Boolean {
        if (config == null) return true
        return mode != config.mode.name ||
            timeAdminPin != config.timeAdminPin ||
            keepAliveEnabled != config.keepAliveEnabled ||
            keepAliveIntervalSeconds != config.keepAliveIntervalSeconds.toString() ||
            failureBehavior != config.failureBehavior.name
    }

    /**
     * Create form state from configuration.
     */
    companion object {
        fun fromConfig(config: TseConfigDto.TseConfig): TseConfigFormState =
            TseConfigFormState(
                mode = config.mode.name,
                // adminPin, adminPuk and credentialSeed stay blank: they are entry-only fields
                // now, typed by the operator for a single setup call and never stored.
                timeAdminPin = config.timeAdminPin,
                keepAliveEnabled = config.keepAliveEnabled,
                keepAliveIntervalSeconds = config.keepAliveIntervalSeconds.toString(),
                failureBehavior = config.failureBehavior.name,
            )
    }

    /**
     * Convert form state to update request DTO.
     */
    fun toUpdateRequest(tseInitialized: Boolean): TseConfigDto.UpdateTseConfigRequest =
        TseConfigDto.UpdateTseConfigRequest(
            mode =
                try {
                    TseMode.valueOf(mode)
                } catch (_: Exception) {
                    null
                },
            // adminPin, adminPuk and credentialSeed are not sent: nothing stores them. They
            // travel on the setup call itself, once, as TseDto.SetupCredentialsRequestDto.
            timeAdminPin = if (tseInitialized) null else timeAdminPin.ifBlank { null },
            keepAliveEnabled = keepAliveEnabled,
            keepAliveIntervalSeconds = keepAliveIntervalSeconds.toIntOrNull(),
            failureBehavior =
                try {
                    TseFailureBehavior.valueOf(failureBehavior)
                } catch (_: Exception) {
                    null
                },
        )
}

/**
 * Result of an admin operation.
 */
sealed class OperationResult {
    data class Success(
        val message: String,
    ) : OperationResult()

    data class Failure(
        val message: String,
    ) : OperationResult()
}
