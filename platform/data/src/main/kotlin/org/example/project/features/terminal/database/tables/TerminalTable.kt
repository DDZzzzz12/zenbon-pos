/**
 * Exposed table definition for terminals.
 *
 * Defines the database schema structure for terminal/client device registration
 * with proper column types, constraints, and relationships for multi-client
 * DSFinV-K compliance.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.terminal.database.tables

import org.example.project.features.setup.database.tables.ShopInfoTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Exposed table definition for terminals table.
 *
 * Manages client device registration for multi-client POS architecture.
 * Each terminal represents a client app instance that connects to the server.
 * Required for DSFinV-K Stamm_Terminals export.
 */
object TerminalTable : LongIdTable("terminals", "id") {
    // device_id is now nullable: an unbound terminal has no device. Unique index still forbids two
    // terminals sharing a device; multiple NULLs are allowed by a unique index in H2 + Postgres.
    /** Unique device identifier from client (UUID generated on first install); null when unbound. */
    val deviceId = varchar("device_id", 100).nullable().uniqueIndex()

    /** Human-readable terminal ID (e.g., "T001", "T002") */
    val clientId = varchar("terminal_id", 50).uniqueIndex("uq_terminals_terminal_id")

    /** Human-friendly terminal name (e.g. "Bar", "Till 1"). */
    val name = varchar("name", 100).nullable()

    /** Terminal brand/platform (e.g., "Android", "Windows", "macOS") */
    val terminalBrand = varchar("terminal_brand", 100).nullable()

    /** Terminal model (e.g., "Samsung Galaxy Tab A8", "Desktop PC") */
    val terminalModel = varchar("terminal_model", 100).nullable()

    /** Terminal serial number or hardware ID */
    val terminalSerialNr = varchar("terminal_serial_nr", 100).nullable()

    /** Software brand (default: "POS-KMP") */
    val terminalSwBrand = varchar("terminal_sw_brand", 100).default("POS-KMP")

    /** Software version (e.g., "1.0.0", "2.1.3") */
    val terminalSwVersion = varchar("terminal_sw_version", 50).nullable()

    /** Terminal active status */
    val isActive = bool("is_active").default(true)

    /** Whether this terminal's client ID is registered with TSE hardware */
    val tseClientRegistered = bool("tse_client_registered").default(false)

    /** Station role name (a TerminalRole value). */
    val role = varchar("role", 40).default("CASHIER")

    /** Client kind the device was paired from (a ClientKind name: NATIVE or BROWSER). */
    val clientKind = varchar("client_kind", 10).default("NATIVE")

    /** Whether this terminal performs TSE-signed transactions (→ has a TSE client). */
    val fiscal = bool("fiscal").default(true)

    /** SHA-256 of the device token issued at pairing (plaintext never stored). */
    val deviceTokenHash = varchar("device_token_hash", 128).nullable()

    /** Expiry of the current device token (time-bounds a leak; rotated on re-pair). */
    val deviceTokenExpiresAt = datetime("device_token_expires_at").nullable()

    /** When the currently bound device was bound. */
    val boundAt = datetime("bound_at").nullable()

    /** Phase-2: physical location/place of the terminal. */
    val location = varchar("location", 150).nullable()

    /** Phase-2: serialized display configuration. */
    val displayConfig = text("display_config").nullable()

    /** Reference to shop this terminal belongs to */
    val shopInfoId = reference("shop_info_id", ShopInfoTable).nullable()

    /** When the terminal was first registered */
    val registeredAt = datetime("registered_at")

    /** When the terminal was last seen/used */
    val lastSeenAt = datetime("last_seen_at")
}
