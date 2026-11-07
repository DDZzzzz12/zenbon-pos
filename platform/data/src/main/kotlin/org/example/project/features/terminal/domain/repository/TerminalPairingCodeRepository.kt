/**
 * Repository interface for TerminalPairingCode entity operations.
 *
 * Defines the contract for pairing-code data access operations.
 * Returns plain entities; services wrap calls in dbQuery with Result.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.terminal.domain.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.terminal.domain.models.TerminalPairingCode

/**
 * Interface defining terminal pairing-code repository operations.
 *
 * Defines the contract for short-lived, single-use pairing-code data access,
 * including issuance, lookup, and consumption/invalidation.
 */
interface TerminalPairingCodeRepository {
    /**
     * Creates a new pairing-code row for [terminal].
     *
     * @param terminal Terminal this code will bind a device to
     * @param code The pairing code shown/entered on the device
     * @param expiresAt When this code expires and can no longer be used
     * @param adminSessionEpoch Non-null only when pairing must register a client the TSE does not list
     * @param registerTseClient Whether pairing with this code registers the terminal's TSE client
     * @return Created TerminalPairingCode entity
     */
    fun create(
        terminal: Terminal,
        code: String,
        expiresAt: LocalDateTime,
        adminSessionEpoch: Long?,
        registerTseClient: Boolean = false,
    ): TerminalPairingCode

    /** Newest unconsumed code row for [code]; expiry/epoch are checked by the service. */
    fun findUnconsumedByCode(code: String): TerminalPairingCode?

    /**
     * The expiry of each terminal's live (unconsumed, unexpired) pairing code.
     *
     * One query for the whole list rather than one per terminal: the back office renders this for
     * every terminal it shows, and a per-row lookup would be an N+1 on the list endpoint. Terminals
     * with no live code are absent from the map.
     */
    fun findLiveExpiryByTerminal(
        terminalIds: List<Long>,
        now: LocalDateTime,
    ): Map<Long, LocalDateTime>

    /**
     * Whether [code] currently identifies a live (unconsumed, unexpired) pairing code for ANY
     * terminal. Used at generation time to guarantee no two currently-live codes share a value,
     * so [findUnconsumedByCode]'s code -> terminal resolution stays unambiguous.
     *
     * @param code The candidate pairing code
     * @param now Current timestamp; a code expiring exactly now still counts as live
     */
    fun isCodeLive(
        code: String,
        now: LocalDateTime,
    ): Boolean

    /**
     * Marks [entity] as consumed at [consumedAt].
     *
     * @param entity TerminalPairingCode entity to consume
     * @param consumedAt Timestamp of consumption
     */
    fun markConsumed(
        entity: TerminalPairingCode,
        consumedAt: LocalDateTime,
    )

    /** Invalidate any outstanding codes for a terminal (called on regenerate + revoke). */
    fun consumeAllForTerminal(
        terminal: Terminal,
        consumedAt: LocalDateTime,
    )
}
