/**
 * Exposed DAO entity for TerminalPairingCode.
 *
 * Provides object-relational mapping for short-lived, single-use pairing codes used to
 * bind a physical device to a terminal.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.terminal.domain.models

import org.example.project.features.terminal.database.tables.TerminalPairingCodeTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/** Exposed DAO entity for a terminal pairing code. */
class TerminalPairingCode(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<TerminalPairingCode>(TerminalPairingCodeTable)

    /** Terminal this pairing code will bind a device to. */
    var terminal by Terminal referencedOn TerminalPairingCodeTable.terminalId

    /** The pairing code shown/entered on the device (short, human-enterable). */
    var code by TerminalPairingCodeTable.code

    /** When this pairing code expires and can no longer be used. */
    var expiresAt by TerminalPairingCodeTable.expiresAt

    /** When this pairing code was consumed by a successful pairing; null while still valid. */
    var consumedAt by TerminalPairingCodeTable.consumedAt

    /** Non-null only when pairing must register the TSE client; binds the code to that TSE admin sitting. */
    var adminSessionEpoch by TerminalPairingCodeTable.adminSessionEpoch

    /** Whether pairing with this code must register the terminal's TSE client (chosen when issued). */
    var registerTseClient by TerminalPairingCodeTable.registerTseClient

    /** When this pairing code was created/issued. */
    var createdAt by TerminalPairingCodeTable.createdAt
}
