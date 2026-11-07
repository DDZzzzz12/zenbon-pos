package org.example.project.admin.features.terminal.presentation

import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.core.presentation.error.UiError

/** `TerminalSummaryDto.clientKind` of a terminal paired from a web browser. */
const val BROWSER_CLIENT_KIND: String = "BROWSER"

/**
 * The link that opens the browser till already pairing: `<origin>/pos/?code=<code>`. [origin] may or
 * may not end in a slash (the address field normalizes to one).
 */
fun browserPairingLink(
    origin: String,
    code: String,
): String = "${origin.trimEnd('/')}/pos/?code=$code"

/** Screen state for the Terminals destination. */
sealed interface TerminalsState {
    data object Loading : TerminalsState

    data class Error(
        val error: UiError,
    ) : TerminalsState

    /**
     * Narrows the list; the counts in the status strip stay over [Data.all], not the filtered view.
     * Declared in the strip's tile order; [TSE_MISSING] is a fiscal terminal with no TSE client.
     */
    enum class TerminalFilter { ALL, ACTIVE, TSE_MISSING, PAIRING_OPEN, INACTIVE }

    /** Column the list is ordered by. */
    enum class TerminalSort { TERMINAL_ID, NAME, ROLE, LAST_SEEN }

    data class Data(
        val all: List<TerminalDto.TerminalSummaryDto>,
        /** Row opened in the side panel, or null when nothing is selected. */
        val selectedId: Long? = null,
        val search: String = "",
        val filter: TerminalFilter = TerminalFilter.ALL,
        val sort: TerminalSort = TerminalSort.TERMINAL_ID,
        val sortAscending: Boolean = true,
        /** Unsaved edits for [selectedId]; null while the panel is only being read. */
        val edit: TerminalEditDraft? = null,
        /** id of a per-row op in flight (revoke / tse-register), so the row can show progress. */
        val busyTerminalId: Long? = null,
        /** id currently generating a pairing code (per-row spinner). */
        val generatingCodeForId: Long? = null,
        /** A non-fatal action error to surface without dropping the list. */
        val actionError: UiError? = null,
        /**
         * Terminal roles currently allowed to run in a browser, or **null when unknown**. The Terminals
         * destination needs only `TERMINAL_MANAGE`, while the setting is behind `CONFIG_READ`, so a
         * terminal manager may be refused the read; unknown means "show no inactive marker", never
         * "nothing is enabled".
         */
        val enabledBrowserRoles: Set<String>? = null,
    ) : TerminalsState {
        /** A browser-paired terminal whose role the shop has switched off — kept, but it cannot sign in. */
        fun isInactiveBrowserStation(terminal: TerminalDto.TerminalSummaryDto): Boolean =
            terminal.clientKind == BROWSER_CLIENT_KIND && enabledBrowserRoles?.let { terminal.role !in it } == true

        val total: Int get() = all.size
        val activeCount: Int get() = all.count { it.isActive }

        /** The status strip's counters: over [all], never over the filtered view. */
        val statusCounts: TerminalStatusCounts get() = TerminalStatusCounts.of(all)

        val selected: TerminalDto.TerminalSummaryDto?
            get() = all.firstOrNull { it.id == selectedId }

        /** The open edit differs from what [TerminalEditDraft.of] builds from the stored terminal. */
        val isEditDirty: Boolean
            get() {
                val draft = edit ?: return false
                val stored = all.firstOrNull { it.id == draft.terminalId } ?: return false
                return draft.differsFrom(TerminalEditDraft.of(stored))
            }

        /**
         * The rows the table shows: filtered, then searched, then sorted.
         *
         * Search covers the terminal number, the operator's own name and the device model, because
         * those are the three things somebody has in hand when they go looking for a till.
         */
        val visible: List<TerminalDto.TerminalSummaryDto>
            get() {
                val term = search.trim().lowercase()
                val matched =
                    all
                        .filter { terminal ->
                            when (filter) {
                                TerminalFilter.ALL -> true
                                TerminalFilter.ACTIVE -> terminal.isActive
                                TerminalFilter.INACTIVE -> !terminal.isActive
                                TerminalFilter.TSE_MISSING -> terminal.isTseMissing()
                                TerminalFilter.PAIRING_OPEN -> terminal.isPairingOpen()
                            }
                        }.filter { terminal ->
                            term.isEmpty() ||
                                terminal.terminalId.lowercase().contains(term) ||
                                terminal.name
                                    .orEmpty()
                                    .lowercase()
                                    .contains(term) ||
                                terminal.terminalModel
                                    .orEmpty()
                                    .lowercase()
                                    .contains(term)
                        }
                val ordered =
                    when (sort) {
                        TerminalSort.TERMINAL_ID -> matched.sortedBy { it.terminalId }
                        // Unnamed terminals sort last either way: an empty name is not a name.
                        TerminalSort.NAME ->
                            matched.sortedBy {
                                it.name
                                    .orEmpty()
                                    .ifEmpty { "\uffff" }
                                    .lowercase()
                            }
                        TerminalSort.ROLE -> matched.sortedBy { it.role }
                        TerminalSort.LAST_SEEN -> matched.sortedBy { it.lastSeenAt }
                    }
                return if (sortAscending) ordered else ordered.reversed()
            }
    }
}

/**
 * Unsaved edits to one terminal.
 *
 * `fiscal` is absent on purpose — the server refuses to change it, because DSFinV-K and the TSE
 * client registration rest on whether a terminal signs. Offering the field and then failing the
 * save would be worse than not offering it.
 */
data class TerminalEditDraft(
    val terminalId: Long,
    val name: String,
    val location: String,
    val role: TerminalRole,
    val saving: Boolean = false,
    val error: UiError? = null,
) {
    /** Name, location or role differ from [baseline]; the transient save flags are ignored. */
    fun differsFrom(baseline: TerminalEditDraft): Boolean = name != baseline.name || location != baseline.location || role != baseline.role

    companion object {
        /** The draft `beginEdit` opens for [terminal]: what the panel shows before anything is typed. */
        fun of(terminal: TerminalDto.TerminalSummaryDto): TerminalEditDraft =
            TerminalEditDraft(
                terminalId = terminal.id,
                name = terminal.name.orEmpty(),
                location = terminal.location.orEmpty(),
                role = TerminalRole.entries.firstOrNull { it.name == terminal.role } ?: TerminalRole.CASHIER,
            )
    }
}

/** Draft for the create-terminal dialog. `fiscal = null` → server derives from [role]'s default. */
data class CreateTerminalDraft(
    val name: String = "",
    val role: TerminalRole = TerminalRole.CASHIER,
    val saving: Boolean = false,
    val error: UiError? = null,
) {
    /** Fiscal capability previewed to the operator = the picked role's provisioning default. */
    val fiscalPreview: Boolean get() = role.defaultFiscal

    /** A name was typed or the role moved off its default. */
    val isDirty: Boolean get() = name.isNotBlank() || role != TerminalRole.CASHIER
}

/** A generated pairing code being presented (with its QR). */
data class PairingCodeView(
    val code: TerminalDto.PairingCodeResponseDto,
)

/**
 * Draft for the admin-PIN unlock dialog raised by a 423 TSE_ADMIN_LOCKED — on tse-register, or on a
 * pairing code asked to register at the TSE. [pendingAction] is what the unlock retries.
 */
data class UnlockDraft(
    val pendingTerminalId: Long,
    val pendingAction: PendingTseAdminAction = PendingTseAdminAction.REGISTER_TSE,
    val pin: String = "",
    val unlocking: Boolean = false,
    val error: UiError? = null,
)
