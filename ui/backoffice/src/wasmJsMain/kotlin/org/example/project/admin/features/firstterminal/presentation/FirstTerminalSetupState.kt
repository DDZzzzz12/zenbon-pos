package org.example.project.admin.features.firstterminal.presentation

import org.example.project.admin.features.terminal.presentation.PairingOptionsDraft
import org.example.project.admin.features.terminal.presentation.PendingTseAdminAction
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.core.presentation.error.UiError

/**
 * Immutable UI state for the new-server first-terminal flow (Terminal Phase RB3 Part B).
 *
 * @property evaluated Guards [FirstTerminalSetupViewModel.evaluateFirstRun] to probe once per session.
 * @property needsFirstTerminal The persistent fact "this server has no terminal yet" — drives the
 *   dashboard banner and the one-shot auto-navigation. Cleared when the first code is issued (DONE).
 * @property autoPrompted Whether the flow was already auto-navigated this session.
 * @property tseModeChecked Whether [FirstTerminalSetupViewModel.loadTseMode] has answered (success or
 *   failure) — until then the Create button stays disabled.
 * @property tseDisabled The TSE is DISABLED (read from the config, or learned from a 409
 *   `TSE_DISABLED` in Step 3). Step 2 is still offered — setup commissions from DISABLED and switches
 *   the shop to NORMAL — together with "continue without TSE", which skips Steps 2 and 3.
 * @property registerError A non-409 Step 3 failure, including a cancelled TSE admin unlock, kept in
 *   state (PosErrorWatcher would clear [error]) so Step 3 can show it next to "retry" and "register
 *   later".
 * @property tseRegistrationDeferred The operator chose "register later" after a Step 3 failure.
 * @property serverUrl Resolved server base URL, the initial address embedded in the pairing QR at DONE.
 * @property pairingOptions Step 4's register-at-the-TSE choice, shown only when that registration is
 *   available (a fiscal terminal whose TSE client is still unregistered, e.g. after "register later");
 *   null = the code is issued straight away without registering.
 * @property unlockRetry What the admin-unlock dialog retries: Step 3's registration or Step 4's code.
 */
data class FirstTerminalSetupState(
    val evaluated: Boolean = false,
    val needsFirstTerminal: Boolean = false,
    val autoPrompted: Boolean = false,
    val step: FirstTerminalStep = FirstTerminalStep.CREATE_TERMINAL,
    val busy: Boolean = false,
    // TSE mode probe — DISABLED skips COMMISSION_TSE and REGISTER_TSE
    val tseModeChecked: Boolean = false,
    val tseDisabled: Boolean = false,
    // Step 1 — create terminal
    val terminalName: String = "",
    val terminalRole: TerminalRole = TerminalRole.CASHIER,
    val createdTerminal: TerminalDto.TerminalResponseDto? = null,
    // Step 2 — commission the TSE with the created terminal as its first client
    val showTseSetupDialog: Boolean = false,
    val tseCommissioned: Boolean = false,
    // Step 3 — register TSE client on an already-commissioned TSE (+ 423 unlock-then-retry, inline failure, deferral)
    val tseClientRegistered: Boolean = false,
    val showAdminUnlockDialog: Boolean = false,
    val adminUnlockPin: String = "",
    val unlocking: Boolean = false,
    val registerError: UiError? = null,
    val tseRegistrationDeferred: Boolean = false,
    // Step 4 — issue pairing code
    val pairingCode: String? = null,
    val pairingRootFingerprint: String? = null,
    val pairingOptions: PairingOptionsDraft? = null,
    val unlockRetry: PendingTseAdminAction = PendingTseAdminAction.REGISTER_TSE,
    val serverUrl: String = "",
    // Shared typed error surfaced by PosErrorWatcher
    val error: UiError? = null,
)
