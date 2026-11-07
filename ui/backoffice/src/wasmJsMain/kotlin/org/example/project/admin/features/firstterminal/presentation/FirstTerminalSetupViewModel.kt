package org.example.project.admin.features.firstterminal.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.browser.window
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.terminal.presentation.PairingOptionsDraft
import org.example.project.admin.features.terminal.presentation.PendingTseAdminAction
import org.example.project.admin.features.terminal.presentation.TseRegistrationOnPairing
import org.example.project.core.data.remote.TerminalApiClient
import org.example.project.core.data.remote.TseSettingsApiClient
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.utils.TseCredentialPlaceholders

/**
 * Orchestrates the new-server first-terminal flow (Terminal Phase RB3 Part B): probe a freshly
 * initialized server for "no terminal yet", then step the manager through create terminal →
 * commission the TSE with that terminal as its first client → issue pairing code + QR.
 *
 * The terminal comes first (owner decision 2026-10-04): setup registers one terminal as the TSE's
 * client, and the back office is no terminal itself, so commissioning before a terminal existed
 * answered 404 on a fresh server. Setup registers the created terminal, so it needs no separate
 * registration; only "the TSE is already commissioned" leads to REGISTER_TSE (in-sitting, with a 423
 * unlock-then-retry). DISABLED is the fresh-install mode and setup commissions from it (switching the
 * shop to NORMAL), so a DISABLED TSE still gets the commissioning step — skipping it there meant a new
 * server could never commission from this flow (QA BO-29). The operator may instead continue without a
 * TSE ([continueWithoutTse]), which skips commission and registration. All calls reuse the shared
 * JWT-gated API clients; nothing on `/api/setup` changes.
 *
 * @param serverUrlProvider resolves the base URL embedded in the pairing QR; defaults to the browser
 *   origin (the admin is served same-origin as pos-server). Injected for tests.
 */
class FirstTerminalSetupViewModel(
    private val terminalApi: TerminalApiClient,
    private val tseSettingsApi: TseSettingsApiClient,
    private val serverUrlProvider: () -> String = { window.location.origin },
) : ViewModel() {
    private val log = Logger.withTag("first-terminal")

    var state by mutableStateOf(FirstTerminalSetupState())
        private set

    private var tseModeProbeJob: Job? = null

    /**
     * Runs once per session, after the admin's first authenticated login: if the server has no
     * terminal yet, arms the flow. Any error (403 for a non-manager, transport failure) leaves
     * [FirstTerminalSetupState.needsFirstTerminal] false — never blocks the app.
     */
    fun evaluateFirstRun() {
        if (state.evaluated) return
        viewModelScope.launch {
            runCatchingApi { terminalApi.getAllTerminals() }
                .onSuccess { terminals ->
                    state =
                        state.copy(
                            evaluated = true,
                            needsFirstTerminal = terminals.isEmpty(),
                            serverUrl = serverUrlProvider(),
                        )
                }.onError { uiError ->
                    log.d { "evaluateFirstRun: not arming first-terminal flow ($uiError)" }
                    state = state.copy(evaluated = true, needsFirstTerminal = false)
                }
        }
    }

    /**
     * Reads the TSE mode each time the flow is opened, as long as no terminal exists yet. A fresh
     * install starts with the TSE DISABLED: there is nothing to commission and no client to register,
     * so a fiscal terminal goes straight from CREATE_TERMINAL to the pairing code. A failed read
     * (e.g. 403 without TSE_ADMIN, transport failure) keeps the full flow and clears any stale
     * [FirstTerminalSetupState.tseDisabled] from an earlier visit — it never blocks the wizard, and it
     * never leaves a fiscal terminal skipping commissioning on a TSE that has since come back. The VM is
     * app-scoped and this can run again on every wizard visit, so a previous in-flight probe is
     * cancelled before starting a new one.
     */
    fun loadTseMode() {
        if (state.createdTerminal != null) return
        state = state.copy(tseModeChecked = false)
        tseModeProbeJob?.cancel()
        tseModeProbeJob =
            viewModelScope.launch {
                runCatchingApi { tseSettingsApi.getConfig() }
                    .onSuccess { config ->
                        val disabled = config.mode == TseMode.DISABLED
                        log.d { "TSE mode ${config.mode}: commission + registration ${if (disabled) "skipped" else "kept"}" }
                        state = state.copy(tseModeChecked = true, tseDisabled = disabled)
                    }.onError { uiError ->
                        log.w { "loadTseMode: TSE mode unreadable, keeping the full flow ($uiError)" }
                        state = state.copy(tseModeChecked = true, tseDisabled = false)
                    }
            }
    }

    /** Marks the one-shot auto-navigation as spent so skipping does not re-open the flow. */
    fun markAutoPrompted() {
        state = state.copy(autoPrompted = true)
    }

    /** Clears the flow (called on logout) so a subsequent login re-probes cleanly. */
    fun reset() {
        state = FirstTerminalSetupState()
    }

    fun clearError() {
        state = state.copy(error = null)
    }

    // Step 1 inputs
    fun updateTerminalName(name: String) {
        state = state.copy(terminalName = name)
    }

    fun updateTerminalRole(role: TerminalRole) {
        state = state.copy(terminalRole = role)
    }

    // Step 2 dialog visibility
    fun showTseSetup() {
        state = state.copy(showTseSetupDialog = true)
    }

    fun hideTseSetup() {
        state = state.copy(showTseSetupDialog = false)
    }

    // Step 3 unlock dialog
    fun updateAdminUnlockPin(pin: String) {
        state = state.copy(adminUnlockPin = pin)
    }

    /**
     * Cancels the Step 3 unlock dialog. Cancelling (or a failed [unlockAndRetryRegister] that the
     * operator then dismisses) leaves the fiscal terminal's TSE client unregistered — without a typed
     * [FirstTerminalSetupState.registerError], Step 3 would dead-end with no retry or defer
     * affordance for a MANAGER who lacks `TSE_ADMIN`. So while still on REGISTER_TSE with no client
     * registered, this raises the same locked error [registerTse] would have shown inline.
     */
    fun hideAdminUnlockDialog() {
        if (state.unlockRetry == PendingTseAdminAction.ISSUE_PAIRING_CODE) {
            // The Step 4 choice stays on screen, so the operator can untick it and pair without registering.
            state =
                state.copy(
                    showAdminUnlockDialog = false,
                    adminUnlockPin = "",
                    error =
                        UiError.FromServer(
                            code = PosErrorCode.TSE_ADMIN_LOCKED,
                            traceId = null,
                            titleKey = AdminStringKeys.TerminalTseUnlockTitle,
                            messageKey = AdminStringKeys.FirstTerminalPairingLockedHint,
                            httpStatus = 423,
                        ),
                )
            return
        }
        val registerError =
            if (state.step == FirstTerminalStep.REGISTER_TSE && !state.tseClientRegistered) {
                UiError.FromServer(
                    code = PosErrorCode.TSE_ADMIN_LOCKED,
                    traceId = null,
                    titleKey = AdminStringKeys.TerminalTseUnlockTitle,
                    messageKey = AdminStringKeys.TerminalSetupTseLockedHint,
                    httpStatus = 423,
                )
            } else {
                state.registerError
            }
        state = state.copy(showAdminUnlockDialog = false, adminUnlockPin = "", registerError = registerError)
    }

    /**
     * Step 2 — commission the TSE via `POST /api/tse/setup`, registering the terminal created in
     * Step 1 as its first client. Credentials travel once and are not stored. Setup writes the
     * TimeAdmin PIN to the device too, so it is saved to the configuration first — the server refuses
     * setup without one — and refused here when blank or a factory placeholder (the dialog disables
     * its button for the same reason). Setup registers the terminal, so a success skips Step 3; a
     * TSE that turns out to be commissioned already continues to Step 3 to register it there.
     */
    fun commissionTse(
        adminPin: String,
        adminPuk: String,
        credentialSeed: String,
        timeAdminPin: String,
    ) {
        if (state.busy) return
        val terminal = state.createdTerminal ?: return
        if (timeAdminPin.isBlank() || TseCredentialPlaceholders.isPlaceholderPin(timeAdminPin)) return
        state = state.copy(busy = true, error = null)
        viewModelScope.launch {
            runCatchingApi {
                tseSettingsApi.updateConfig(TseConfigDto.UpdateTseConfigRequest(timeAdminPin = timeAdminPin))
                tseSettingsApi.setup(
                    TseDto.SetupCredentialsRequestDto(
                        adminPin = adminPin,
                        adminPuk = adminPuk,
                        credentialSeed = credentialSeed,
                        terminalId = terminal.terminalId,
                    ),
                )
            }.onSuccess { result ->
                state =
                    if (result.success) {
                        log.d { "TSE commissioned with client ${terminal.terminalId}" }
                        state.copy(
                            busy = false,
                            tseCommissioned = true,
                            tseClientRegistered = true,
                            // Setup saves the mode as NORMAL, so the DONE step must not call it disabled.
                            tseDisabled = false,
                            showTseSetupDialog = false,
                            step = FirstTerminalStep.ISSUE_CODE,
                        )
                    } else {
                        log.w { "TSE commission returned success=false: ${result.message}" }
                        state.copy(
                            busy = false,
                            error =
                                UiError.FromServer(
                                    code = PosErrorCode.TSE_SETUP_FAILED,
                                    traceId = null,
                                    titleKey = CommonKeys.unknownErrorTitle,
                                    messageKey = AdminStringKeys.FirstTerminalErrorCommission,
                                    httpStatus = 0,
                                    rawDetail = result.message,
                                ),
                        )
                    }
            }.onError { uiError ->
                if (uiError.code == PosErrorCode.TSE_ALREADY_INITIALIZED) {
                    log.w { "TSE already commissioned — registering the terminal instead" }
                    state = state.copy(busy = false, showTseSetupDialog = false, step = FirstTerminalStep.REGISTER_TSE)
                    return@onError
                }
                log.e { "TSE commission failed" }
                state = state.copy(busy = false, showTseSetupDialog = false, error = uiError)
            }
        }
    }

    /**
     * Only while the TSE is DISABLED: the operator runs without a TSE, so commission and registration
     * are both skipped and the pairing code is issued straight away. With the TSE enabled there is no
     * such way past Step 2 — a fiscal terminal must sign.
     */
    fun continueWithoutTse() {
        if (state.step != FirstTerminalStep.COMMISSION_TSE || !state.tseDisabled || state.busy) return
        state = state.copy(step = FirstTerminalStep.ISSUE_CODE)
    }

    /** Manager asserts the TSE is already commissioned — register the new terminal on it (Step 3). */
    fun skipTseCommission() {
        if (state.step != FirstTerminalStep.COMMISSION_TSE) return
        state = state.copy(step = FirstTerminalStep.REGISTER_TSE)
    }

    /**
     * Step 1 — provision the first terminal. `fiscal = null` lets the server default it from the role
     * ([TerminalRole.defaultFiscal]). A fiscal terminal proceeds to commissioning (Step 2) — also while
     * the TSE is DISABLED, where Step 2 additionally offers [continueWithoutTse]; a non-fiscal one skips
     * to Step 4. A no-op while [loadTseMode] has not yet answered — [FirstTerminalSetupState.tseDisabled]
     * could still be stale from an earlier visit, and Step 2 would offer the wrong choices.
     */
    fun createTerminal() {
        if (!state.tseModeChecked) return
        state = state.copy(busy = true, error = null)
        viewModelScope.launch {
            runCatchingApi {
                terminalApi.createTerminal(
                    TerminalDto.CreateTerminalRequestDto(
                        name = state.terminalName.trim().ifBlank { null },
                        role = state.terminalRole.name,
                        fiscal = null,
                    ),
                )
            }.onSuccess { dto ->
                log.d { "terminal ${dto.id} created (role=${dto.role}, fiscal=${dto.fiscal})" }
                state =
                    state.copy(
                        busy = false,
                        createdTerminal = dto,
                        // DISABLED is commissionable (setup switches it to NORMAL); Step 2 offers to skip it.
                        step = if (dto.fiscal) FirstTerminalStep.COMMISSION_TSE else FirstTerminalStep.ISSUE_CODE,
                    )
            }.onError { uiError ->
                log.e { "create terminal failed" }
                state = state.copy(busy = false, error = uiError)
            }
        }
    }

    /**
     * Step 4 — issue the first single-use pairing code. The screen renders it plus a QR of
     * `PairingQrPayload(serverUrl, code)`; the fresh device scans it to learn the server URL and
     * pair. On success `needsFirstTerminal` is cleared — the server is no longer empty.
     *
     * A fiscal terminal whose TSE client is still unregistered (Step 3 deferred) first gets the
     * register-at-the-TSE choice when the TSE can take it ([FirstTerminalSetupState.pairingOptions],
     * ticked by default); every other terminal gets its code straight away, registering nothing.
     */
    fun issuePairingCode() {
        val terminal = state.createdTerminal ?: return
        if (state.busy || state.pairingOptions != null) return
        val mayRegister = terminal.fiscal && !state.tseDisabled && !state.tseClientRegistered
        if (!mayRegister) {
            issueCode(registerTseClient = false)
            return
        }
        state = state.copy(busy = true, error = null)
        viewModelScope.launch {
            val status =
                runCatchingApi { terminalApi.getTsePairingStatus() }
                    .onFailure { log.w { "TSE pairing status unreadable — issuing without registration: $it" } }
                    .getOrNull()
            val availability = TseRegistrationOnPairing.of(fiscal = true, tseClientRegistered = false, status = status)
            if (!availability.selectable) {
                state = state.copy(busy = false)
                issueCode(registerTseClient = false)
                return@launch
            }
            state =
                state.copy(
                    busy = false,
                    pairingOptions = PairingOptionsDraft(terminal.id, availability = availability, registerTseClient = true),
                )
        }
    }

    fun onRegisterTseClient(checked: Boolean) {
        val options = state.pairingOptions ?: return
        if (options.availability?.selectable != true) return
        state = state.copy(pairingOptions = options.copy(registerTseClient = checked))
    }

    /** Issues the Step 4 code with the operator's register-at-the-TSE choice. */
    fun confirmIssuePairingCode() {
        val options = state.pairingOptions ?: return
        issueCode(registerTseClient = options.requestsTseRegistration)
    }

    /** A 423 on a registering code raises the admin-unlock dialog; [unlockAndRetryRegister] then re-issues it. */
    private fun issueCode(registerTseClient: Boolean) {
        val id = state.createdTerminal?.id ?: return
        if (state.busy) return
        state = state.copy(busy = true, error = null)
        viewModelScope.launch {
            val result = runCatchingApi { terminalApi.generatePairingCode(id, registerTseClient) }
            if (registerTseClient && (result.exceptionOrNull() as? PosApiException)?.code == PosErrorCode.TSE_ADMIN_LOCKED) {
                log.d { "registering pairing code hit a locked TSE admin sitting — prompting for Admin PIN" }
                state =
                    state.copy(
                        busy = false,
                        showAdminUnlockDialog = true,
                        adminUnlockPin = "",
                        unlockRetry = PendingTseAdminAction.ISSUE_PAIRING_CODE,
                    )
                return@launch
            }
            result
                .onSuccess { resp ->
                    log.d { "pairing code issued for terminal $id (registerTseClient=$registerTseClient)" }
                    state =
                        state.copy(
                            busy = false,
                            pairingCode = resp.code,
                            pairingRootFingerprint = resp.rootFingerprint,
                            pairingOptions = null,
                            step = FirstTerminalStep.DONE,
                            needsFirstTerminal = false,
                            // A code that registers on pairing settles the deferred registration.
                            tseRegistrationDeferred = state.tseRegistrationDeferred && !registerTseClient,
                        )
                }.onError { uiError ->
                    log.e { "issue pairing code failed" }
                    state = state.copy(busy = false, error = uiError)
                }
        }
    }

    /**
     * Step 3 — register the fiscal terminal's TSE client on an already-commissioned TSE. A
     * `TSE_ADMIN_LOCKED` (HTTP 423) means the sitting lapsed: raise the unlock dialog and let
     * [unlockAndRetryRegister] resume — the same 423-is-the-prompt pattern used by the terminal
     * back-office and RB4's TSE-admin surface. A `TSE_DISABLED` (HTTP 409) means there is nothing to
     * register: flag the TSE as disabled and continue to the pairing code. Any other failure stays on
     * Step 3 with [FirstTerminalSetupState.registerError] shown inline, so the operator can call this
     * again or [deferTseRegistration]. A no-op while a call is already running, like [deferTseRegistration].
     */
    fun registerTse() {
        if (state.busy) return
        val id = state.createdTerminal?.id ?: return
        state = state.copy(busy = true, error = null, registerError = null)
        viewModelScope.launch {
            val result = runCatchingApi { terminalApi.registerTerminalTse(id) }
            when ((result.exceptionOrNull() as? PosApiException)?.code) {
                PosErrorCode.TSE_ADMIN_LOCKED -> {
                    log.d { "TSE admin sitting locked — prompting for Admin PIN" }
                    state =
                        state.copy(
                            busy = false,
                            showAdminUnlockDialog = true,
                            adminUnlockPin = "",
                            unlockRetry = PendingTseAdminAction.REGISTER_TSE,
                        )
                    return@launch
                }
                PosErrorCode.TSE_DISABLED -> {
                    log.w { "TSE is disabled — skipping TSE client registration for terminal $id" }
                    state = state.copy(busy = false, tseDisabled = true, step = FirstTerminalStep.ISSUE_CODE)
                    return@launch
                }
                else -> Unit
            }
            result
                .onSuccess { summary ->
                    log.d { "TSE client registered for terminal $id" }
                    state =
                        state.copy(
                            busy = false,
                            tseClientRegistered = summary.tseClientRegistered,
                            step = FirstTerminalStep.ISSUE_CODE,
                        )
                }.onError { uiError ->
                    log.e { "register TSE client failed — staying on Step 3 ($uiError)" }
                    state = state.copy(busy = false, registerError = uiError)
                }
        }
    }

    /**
     * The operator's explicit way past a failed Step 3: issue the pairing code anyway and register the
     * TSE client later in the Terminals screen. Only acts on REGISTER_TSE while no call is running;
     * [FirstTerminalSetupState.registerError] is cleared — the DONE step's note is driven by
     * [FirstTerminalSetupState.tseRegistrationDeferred], not by the original failure staying on record.
     */
    fun deferTseRegistration() {
        if (state.step != FirstTerminalStep.REGISTER_TSE || state.busy) return
        log.w { "TSE client registration deferred for terminal ${state.createdTerminal?.id}" }
        state = state.copy(step = FirstTerminalStep.ISSUE_CODE, tseRegistrationDeferred = true, registerError = null)
    }

    /**
     * Starts an admin sitting with the operator-supplied PIN, then retries what raised the dialog —
     * [registerTse], or Step 4's registering pairing code ([FirstTerminalSetupState.unlockRetry]). The PIN is
     * not retained here — it lives only in the server's in-memory sitting. A rejected PIN
     * (`unlocked = false`) keeps the dialog open with a localized unlock-failed error.
     */
    fun unlockAndRetryRegister() {
        val pin = state.adminUnlockPin
        if (pin.isBlank()) return
        state = state.copy(unlocking = true, error = null)
        viewModelScope.launch {
            runCatchingApi { tseSettingsApi.unlockAdmin(pin) }
                .onSuccess { status ->
                    if (status.unlocked) {
                        state = state.copy(unlocking = false, showAdminUnlockDialog = false, adminUnlockPin = "")
                        when (state.unlockRetry) {
                            PendingTseAdminAction.REGISTER_TSE -> registerTse()
                            PendingTseAdminAction.ISSUE_PAIRING_CODE -> issueCode(registerTseClient = true)
                            // Never raised here: first-terminal setup registers, it never deregisters.
                            PendingTseAdminAction.DEREGISTER_TSE -> Unit
                        }
                    } else {
                        log.w { "TSE admin unlock rejected (wrong PIN)" }
                        state =
                            state.copy(
                                unlocking = false,
                                adminUnlockPin = "",
                                error =
                                    UiError.FromServer(
                                        code = PosErrorCode.TSE_INVALID_PIN,
                                        traceId = null,
                                        titleKey = CommonKeys.unknownErrorTitle,
                                        messageKey = AdminStringKeys.FirstTerminalErrorUnlock,
                                        httpStatus = 0,
                                    ),
                            )
                    }
                }.onError { uiError ->
                    log.e { "TSE admin unlock failed" }
                    state = state.copy(unlocking = false, adminUnlockPin = "", error = uiError)
                }
        }
    }
}
