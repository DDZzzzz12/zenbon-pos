/**
 * Exposed table definition for terminal pairing codes.
 *
 * Defines the database schema structure for short-lived, single-use pairing codes
 * used to bind a physical device to a terminal.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.terminal.database.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Short-lived, single-use pairing codes used to bind a physical device to a terminal.
 * A code is consumed on successful pairing; expired/consumed codes are ignored.
 *
 * `adminSessionEpoch` is set ONLY on a code issued with `registerTseClient` for a terminal the TSE
 * does not list yet; a non-null value binds the code to the issuing TSE admin sitting, under which
 * pairing registers the client. NULL = plain-TTL validity.
 */
object TerminalPairingCodeTable : LongIdTable("terminal_pairing_codes", "id") {
    /** Terminal this pairing code will bind a device to. */
    val terminalId = reference("terminal_id", TerminalTable)

    /** The pairing code shown/entered on the device (short, human-enterable). */
    val code = varchar("code", 12).index("idx_terminal_pairing_codes_code")

    /** When this pairing code expires and can no longer be used. */
    val expiresAt = datetime("expires_at")

    /** When this pairing code was consumed by a successful pairing; null while still valid. */
    val consumedAt = datetime("consumed_at").nullable()

    /** Non-null only when pairing must register the TSE client; binds the code to that TSE admin sitting. */
    val adminSessionEpoch = long("admin_session_epoch").nullable()

    /**
     * Whether pairing with this code must register the terminal's TSE client (operator's choice when
     * issuing it). False = pair without touching the TSE, as every code did before F42.
     */
    val registerTseClient = bool("register_tse_client").default(false)

    /** When this pairing code was created/issued. */
    val createdAt = datetime("created_at")
}
