package org.example.project.admin.features.terminal.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.data.remote.ShopConfigurationApiClient
import org.example.project.core.data.remote.TerminalApiClient
import org.example.project.core.data.remote.TseSettingsApiClient
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.keys.CommonKeys

/** adminApp is single-tenant, matching the other config ViewModels. */
private const val DEFAULT_SHOP_ID: Long = 1L

/**
 * Back-office terminal management for the wasm admin. Lists terminals and issues the
 * MANAGER-gated create / pairing-code / revoke / tse-register actions. All clientId-needing
 * TSE work happens server-side; this VM only sends the admin-PIN unlock command on a 423 (from
 * tse-register, or from a pairing code asked to register the TSE client while pairing).
 */
class TerminalsViewModel(
    private val terminalApi: TerminalApiClient,
    private val tseAdminApi: TseSettingsApiClient,
    private val shopConfigApi: ShopConfigurationApiClient,
    private val shopId: Long = DEFAULT_SHOP_ID,
) : ViewModel() {
    private val log = Logger.withTag("terminals")

    var state by mutableStateOf<TerminalsState>(TerminalsState.Loading)
        private set

    /** Transient create-dialog draft; null = closed. */
    var createDraft by mutableStateOf<CreateTerminalDraft?>(null)
        private set

    /** The "generate pairing code" dialog (register-at-the-TSE choice) before a code exists; null = closed. */
    var pairingOptions by mutableStateOf<PairingOptionsDraft?>(null)
        private set

    /** A generated pairing code being presented; null = no dialog. */
    var pairing by mutableStateOf<PairingCodeView?>(null)
        private set

    /** Admin-PIN unlock draft raised by a 423 on tse-register or on a registering pairing code; null = no dialog. */
    var unlock by mutableStateOf<UnlockDraft?>(null)
        private set

    private inline fun withData(block: (TerminalsState.Data) -> TerminalsState.Data) {
        (state as? TerminalsState.Data)?.let { state = block(it) }
    }

    fun clearError() {
        if (state is TerminalsState.Error) {
            state = TerminalsState.Loading
        }
    }

    /** Clears a per-action error (register/revoke/pairing code); the top-level state is untouched. */
    fun clearActionError() = withData { it.copy(actionError = null) }

    private var filterToRestore: TerminalsState.Data? = null

    /** Reloads the list, keeping the applied search, status filter and sort across a rebuilt destination. */
    fun refresh() {
        // Loading/Error carry no filter: a second refresh during a load (or a retry) reuses the first one's.
        val applied = (state as? TerminalsState.Data) ?: filterToRestore
        filterToRestore = applied
        state = TerminalsState.Loading
        viewModelScope.launch {
            runCatchingApi { terminalApi.getAllTerminals() }
                .onSuccess { all ->
                    filterToRestore = null
                    state =
                        TerminalsState.Data(
                            all = all,
                            enabledBrowserRoles = loadEnabledBrowserRoles(),
                            search = applied?.search.orEmpty(),
                            filter = applied?.filter ?: TerminalsState.TerminalFilter.ALL,
                            sort = applied?.sort ?: TerminalsState.TerminalSort.TERMINAL_ID,
                            sortAscending = applied?.sortAscending ?: true,
                        )
                }.onError(retry = ::refresh) { uiError ->
                    log.e { "load terminals failed" }
                    state = TerminalsState.Error(uiError)
                }
        }
    }

    /**
     * The roles allowed to run in a browser, or null when the read is refused (this screen needs
     * `TERMINAL_MANAGE`, the setting `CONFIG_READ`) or fails. Never an error the operator sees: the
     * inactive marker is a convenience, and unknown simply means no marker.
     */
    private suspend fun loadEnabledBrowserRoles(): Set<String>? =
        runCatchingApi { shopConfigApi.getBrowserStations(shopId) }
            .onFailure { log.w { "browser-station roles unreadable, showing no inactive marker: $it" } }
            .getOrNull()
            ?.roles
            ?.toSet()

    private suspend fun reload(): UiError? {
        var error: UiError? = null
        runCatchingApi { terminalApi.getAllTerminals() }
            .onSuccess { all -> withData { it.copy(all = all) } }
            .onError { uiError ->
                log.e { "reload terminals failed: $uiError" }
                error = uiError
            }
        return error
    }

    // ---------- Create ----------

    // --- list controls ----------------------------------------------------------------------

    fun onSearch(term: String) = withData { it.copy(search = term) }

    fun onFilter(filter: TerminalsState.TerminalFilter) = withData { it.copy(filter = filter) }

    /** Clicking the sorted column again reverses it, which is what every other table here does. */
    fun onSort(sort: TerminalsState.TerminalSort) =
        withData {
            if (it.sort == sort) it.copy(sortAscending = !it.sortAscending) else it.copy(sort = sort, sortAscending = true)
        }

    // --- selection and editing --------------------------------------------------------------

    /** Opens the side panel, discarding any unsaved edit on the row that was open before. */
    fun select(terminalId: Long?) = withData { it.copy(selectedId = terminalId, edit = null) }

    fun beginEdit() =
        withData { data ->
            val terminal = data.all.firstOrNull { it.id == data.selectedId } ?: return@withData data
            data.copy(
                edit = TerminalEditDraft.of(terminal),
            )
        }

    fun onEditField(block: (TerminalEditDraft) -> TerminalEditDraft) = withData { data -> data.edit?.let { data.copy(edit = block(it)) } ?: data }

    fun cancelEdit() = withData { it.copy(edit = null) }

    fun saveEdit() {
        val draft = (state as? TerminalsState.Data)?.edit ?: return
        if (draft.saving) return
        onEditField { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            runCatchingApi {
                terminalApi.updateTerminal(
                    draft.terminalId,
                    TerminalDto.UpdateTerminalRequestDto(
                        name = draft.name,
                        location = draft.location,
                        role = draft.role.name,
                    ),
                )
            }.onSuccess {
                // Reload rather than patch the row in place: the response is a
                // TerminalResponseDto and the list holds summaries, and reload is already the
                // pattern every other mutating action here uses.
                val reloadError = reload()
                withData { data ->
                    if (reloadError == null) {
                        data.copy(edit = null)
                    } else {
                        data.copy(edit = data.edit?.copy(saving = false, error = reloadError))
                    }
                }
            }.onError { error ->
                onEditField { it.copy(saving = false, error = error) }
            }
        }
    }

    fun openCreate() {
        createDraft = CreateTerminalDraft()
    }

    fun onCreateField(block: (CreateTerminalDraft) -> CreateTerminalDraft) {
        createDraft = createDraft?.let(block)
    }

    fun closeCreate() {
        createDraft = null
    }

    /**
     * Provisions the terminal (fiscal = null → server derives from the role), reloads the
     * list, then opens the pairing-code dialog so the manager can choose TSE registration and
     * present the QR.
     */
    fun submitCreate() {
        val d = createDraft ?: return
        createDraft = d.copy(saving = true, error = null)
        viewModelScope.launch {
            runCatchingApi {
                terminalApi.createTerminal(
                    TerminalDto.CreateTerminalRequestDto(
                        name = d.name.ifBlank { null },
                        role = d.role.name,
                        fiscal = null,
                    ),
                )
            }.onSuccess { created ->
                log.d { "terminal created id=${created.id} role=${d.role.name}" }
                createDraft = null
                // The terminal exists from here on. A failing list refresh must surface on the
                // screen — the draft is gone, so it can no longer carry the error — and must not
                // stop the pairing code the operator is waiting for. The error is re-applied
                // AFTER the code round-trip, which clears actionError on entry.
                val reloadError = reload()
                reloadError?.let { uiError -> withData { it.copy(actionError = uiError) } }
                showPairingOptions(created.id, fiscal = created.fiscal, tseClientRegistered = created.tseClientRegistered)
            }.onError { uiError ->
                log.e { "create terminal failed: $uiError" }
                createDraft = createDraft?.copy(saving = false, error = uiError)
            }
        }
    }

    // ---------- Pairing code ----------

    /**
     * Opens the pairing-code dialog for a listed terminal. The register-at-the-TSE box defaults to
     * ticked exactly when it is available (fiscal, not yet registered, TSE NORMAL + commissioned).
     */
    fun openPairingOptions(terminalId: Long) {
        val terminal = (state as? TerminalsState.Data)?.all?.firstOrNull { it.id == terminalId }
        viewModelScope.launch {
            showPairingOptions(
                terminalId = terminalId,
                fiscal = terminal?.fiscal ?: false,
                tseClientRegistered = terminal?.tseClientRegistered ?: false,
            )
        }
    }

    private suspend fun showPairingOptions(
        terminalId: Long,
        fiscal: Boolean,
        tseClientRegistered: Boolean,
    ) {
        pairingOptions = PairingOptionsDraft(terminalId = terminalId)
        // Only a fiscal, unregistered terminal needs the TSE asked at all.
        val status =
            if (fiscal && !tseClientRegistered) {
                runCatchingApi { terminalApi.getTsePairingStatus() }
                    .onFailure { log.w { "TSE pairing status unreadable: $it" } }
                    .getOrNull()
            } else {
                null
            }
        val availability = TseRegistrationOnPairing.of(fiscal, tseClientRegistered, status)
        pairingOptions =
            pairingOptions
                ?.takeIf { it.terminalId == terminalId }
                ?.copy(availability = availability, registerTseClient = availability.selectable)
    }

    fun onRegisterTseClient(checked: Boolean) {
        pairingOptions = pairingOptions?.let { if (it.availability?.selectable == true) it.copy(registerTseClient = checked) else it }
    }

    fun closePairingOptions() {
        pairingOptions = null
    }

    /** Issues the code with the dialog's choice. A no-op while the TSE status is still loading. */
    fun confirmPairingCode() {
        val options = pairingOptions ?: return
        if (options.availability == null) return
        pairingOptions = null
        viewModelScope.launch { doGeneratePairingCode(options.terminalId, options.requestsTseRegistration) }
    }

    /** On a 423 for a registering code raise the unlock prompt and retry after it; other errors surface as UiError. */
    private suspend fun doGeneratePairingCode(
        terminalId: Long,
        registerTseClient: Boolean,
    ) {
        withData { it.copy(generatingCodeForId = terminalId, actionError = null) }
        val result = runCatchingApi { terminalApi.generatePairingCode(terminalId, registerTseClient) }
        if ((result.exceptionOrNull() as? PosApiException)?.code == PosErrorCode.TSE_ADMIN_LOCKED) {
            log.d { "registering pairing code for $terminalId hit locked admin sitting → prompting for PIN" }
            withData { it.copy(generatingCodeForId = null) }
            unlock = UnlockDraft(pendingTerminalId = terminalId, pendingAction = PendingTseAdminAction.ISSUE_PAIRING_CODE)
            return
        }
        result
            .onSuccess { code ->
                log.d { "pairing code generated for terminal $terminalId (registerTseClient=$registerTseClient)" }
                pairing = PairingCodeView(code)
                withData { it.copy(generatingCodeForId = null) }
            }.onError { uiError ->
                log.e { "generate pairing code failed for $terminalId" }
                withData { it.copy(generatingCodeForId = null, actionError = uiError) }
            }
    }

    fun dismissPairingCode() {
        pairing = null
    }

    // ---------- Revoke ----------

    fun revokeBinding(terminalId: Long) {
        withData { it.copy(busyTerminalId = terminalId, actionError = null) }
        viewModelScope.launch {
            runCatchingApi { terminalApi.revokeBinding(terminalId) }
                .onSuccess {
                    log.d { "binding revoked for terminal $terminalId" }
                    reload()
                    withData { it.copy(busyTerminalId = null) }
                }.onError { uiError ->
                    log.e { "revoke binding failed for $terminalId" }
                    withData { it.copy(busyTerminalId = null, actionError = uiError) }
                }
        }
    }

    /**
     * Deactivates the terminal: it stays paired but cannot sell until reactivated. Different from
     * [revokeBinding], which unbinds the device and forces re-pairing (E2E-2026-09-18 #17).
     */
    fun deactivate(terminalId: Long) = runTerminalAction(terminalId, "deactivate") { terminalApi.deactivateTerminal(terminalId) }

    /** Reactivates a deactivated terminal; its pairing and TSE registration were never touched. */
    fun reactivate(terminalId: Long) = runTerminalAction(terminalId, "reactivate") { terminalApi.reactivateTerminal(terminalId) }

    private fun runTerminalAction(
        terminalId: Long,
        name: String,
        call: suspend () -> Unit,
    ) {
        withData { it.copy(busyTerminalId = terminalId, actionError = null) }
        viewModelScope.launch {
            runCatchingApi { call() }
                .onSuccess {
                    log.d { "$name succeeded for terminal $terminalId" }
                    reload()
                    withData { it.copy(busyTerminalId = null) }
                }.onError { uiError ->
                    log.e { "$name failed for $terminalId" }
                    withData { it.copy(busyTerminalId = null, actionError = uiError) }
                }
        }
    }

    // ---------- TSE register (+ 423 unlock/retry) ----------

    fun registerTerminalTse(terminalId: Long) {
        withData { it.copy(busyTerminalId = terminalId, actionError = null) }
        viewModelScope.launch { doRegisterTse(terminalId) }
    }

    /** Shared body: on a 423 TSE_ADMIN_LOCKED raise the unlock prompt; other errors surface as UiError. */
    private suspend fun doRegisterTse(terminalId: Long) {
        val result = runCatchingApi { terminalApi.registerTerminalTse(terminalId) }
        val locked = (result.exceptionOrNull() as? PosApiException)?.code == PosErrorCode.TSE_ADMIN_LOCKED
        if (locked) {
            log.d { "tse-register for $terminalId hit locked admin sitting → prompting for PIN" }
            withData { it.copy(busyTerminalId = null) }
            unlock = UnlockDraft(pendingTerminalId = terminalId)
            return
        }
        result
            .onSuccess {
                log.d { "terminal $terminalId TSE-registered" }
                reload()
                withData { it.copy(busyTerminalId = null) }
            }.onError { uiError ->
                log.e { "tse-register failed for $terminalId" }
                withData { it.copy(busyTerminalId = null, actionError = uiError) }
            }
    }

    // ---------- TSE deregister (+ 423 unlock/retry) ----------

    /**
     * Removes a retired terminal's TSE client. Refused here, without a call, for a terminal the list
     * already shows as active or bound; the server re-checks those and the guards only it can see.
     */
    fun deregisterTerminalTse(terminalId: Long) {
        val terminal = (state as? TerminalsState.Data)?.all?.firstOrNull { it.id == terminalId } ?: return
        if (tseDeregisterBlockReason(terminal) != null) return
        withData { it.copy(busyTerminalId = terminalId, actionError = null) }
        viewModelScope.launch { doDeregisterTse(terminalId) }
    }

    private suspend fun doDeregisterTse(terminalId: Long) {
        val result = runCatchingApi { terminalApi.deregisterTerminalTse(terminalId) }
        if ((result.exceptionOrNull() as? PosApiException)?.code == PosErrorCode.TSE_ADMIN_LOCKED) {
            log.d { "tse-deregister for $terminalId hit locked admin sitting → prompting for PIN" }
            withData { it.copy(busyTerminalId = null) }
            unlock = UnlockDraft(pendingTerminalId = terminalId, pendingAction = PendingTseAdminAction.DEREGISTER_TSE)
            return
        }
        result
            .onSuccess {
                log.d { "terminal $terminalId TSE client deregistered" }
                reload()
                withData { it.copy(busyTerminalId = null) }
            }.onError { uiError ->
                log.e { "tse-deregister failed for $terminalId" }
                withData { it.copy(busyTerminalId = null, actionError = uiError) }
            }
    }

    fun onUnlockPin(pin: String) {
        unlock = unlock?.copy(pin = pin, error = null)
    }

    fun closeUnlock() {
        unlock = null
    } // never retain the PIN

    /** Starts an admin sitting with the operator PIN (server-side, memory only), then retries. */
    fun unlockAndRetry() {
        val u = unlock ?: return
        unlock = u.copy(unlocking = true, error = null)
        viewModelScope.launch {
            runCatchingApi { tseAdminApi.unlockAdmin(u.pin) }
                .onSuccess { status ->
                    if (!status.unlocked) {
                        // The server accepted the call but no sitting is open — retrying would just
                        // bounce off another 423, so keep the dialog up with the failure shown.
                        log.w { "admin unlock did not open a sitting for ${u.pendingTerminalId}" }
                        unlock =
                            unlock?.copy(
                                unlocking = false,
                                pin = "",
                                error =
                                    UiError.FromServer(
                                        code = PosErrorCode.TSE_INVALID_PIN,
                                        traceId = null,
                                        titleKey = CommonKeys.unknownErrorTitle,
                                        messageKey = AdminStringKeys.TerminalTseUnlockError,
                                        httpStatus = 0,
                                    ),
                            )
                        return@launch
                    }
                    log.d { "admin sitting unlocked; retrying ${u.pendingAction} for ${u.pendingTerminalId}" }
                    val terminalId = u.pendingTerminalId
                    unlock = null
                    when (u.pendingAction) {
                        PendingTseAdminAction.REGISTER_TSE -> {
                            withData { it.copy(busyTerminalId = terminalId) }
                            doRegisterTse(terminalId)
                        }
                        PendingTseAdminAction.ISSUE_PAIRING_CODE -> doGeneratePairingCode(terminalId, registerTseClient = true)
                        PendingTseAdminAction.DEREGISTER_TSE -> {
                            withData { it.copy(busyTerminalId = terminalId) }
                            doDeregisterTse(terminalId)
                        }
                    }
                }.onError { uiError ->
                    log.e { "admin unlock failed for ${u.pendingTerminalId}" }
                    unlock = unlock?.copy(unlocking = false, error = uiError)
                }
        }
    }
}
