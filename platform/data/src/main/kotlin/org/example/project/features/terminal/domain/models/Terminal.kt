/**
 * Exposed DAO entity for Terminal.
 *
 * Provides object-relational mapping for terminals with proper relationship
 * management and type-safe database operations. Supports multi-client terminal
 * registration for DSFinV-K compliance.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.terminal.domain.models

import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.terminal.database.tables.TerminalTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/**
 * Terminal entity for client device registration.
 *
 * Manages client app instances that connect to the POS server.
 * Each terminal gets a unique ID for DSFinV-K TERMINAL_ID field tracking.
 */
class Terminal(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<Terminal>(TerminalTable)

    /** Unique device identifier from client; null when the terminal is unbound. */
    var deviceId by TerminalTable.deviceId

    /** Human-readable terminal ID */
    var clientId by TerminalTable.clientId

    /** Human-friendly terminal name (e.g. "Bar", "Till 1"). */
    var name by TerminalTable.name

    /** Terminal brand/platform */
    var terminalBrand by TerminalTable.terminalBrand

    /** Terminal model */
    var terminalModel by TerminalTable.terminalModel

    /** Terminal serial number */
    var terminalSerialNr by TerminalTable.terminalSerialNr

    /** Software brand */
    var terminalSwBrand by TerminalTable.terminalSwBrand

    /** Software version */
    var terminalSwVersion by TerminalTable.terminalSwVersion

    /** Terminal active status */
    var isActive by TerminalTable.isActive

    /** Whether this terminal's client ID is registered with TSE hardware */
    var tseClientRegistered by TerminalTable.tseClientRegistered

    /** Station role name (a TerminalRole value). */
    var role by TerminalTable.role

    /** Client kind the device was paired from (a ClientKind name: NATIVE or BROWSER). */
    var clientKind by TerminalTable.clientKind

    /** Whether this terminal performs TSE-signed transactions (→ has a TSE client). */
    var fiscal by TerminalTable.fiscal

    /** SHA-256 of the device token issued at pairing (plaintext never stored). */
    var deviceTokenHash by TerminalTable.deviceTokenHash

    /** Expiry of the current device token (time-bounds a leak; rotated on re-pair). */
    var deviceTokenExpiresAt by TerminalTable.deviceTokenExpiresAt

    /** When the currently bound device was bound. */
    var boundAt by TerminalTable.boundAt

    /** Phase-2: physical location/place of the terminal. */
    var location by TerminalTable.location

    /** Phase-2: serialized display configuration. */
    var displayConfig by TerminalTable.displayConfig

    /** Reference to shop */
    var shopInfo by ShopInfo optionalReferencedOn TerminalTable.shopInfoId

    /** Registration timestamp */
    var registeredAt by TerminalTable.registeredAt

    /** Last seen timestamp */
    var lastSeenAt by TerminalTable.lastSeenAt
}
