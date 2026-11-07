/**
 * Container object for Terminal-related DTOs.
 *
 * Organizes all data transfer objects for terminal registration
 * and management in the multi-client POS architecture.
 *
 * Used by both server and client for terminal registration API.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.dto.terminal

import kotlinx.serialization.Serializable
import org.example.project.core.domain.enums.setup.TseMode

/**
 * Container object for Terminal DTOs.
 *
 * Groups all data transfer objects related to terminal registration
 * and management for multi-client DSFinV-K compliance.
 */
object TerminalDto {
    /**
     * Request DTO for registering or updating a terminal.
     *
     * @property model Device model information
     * @property brand Device brand/platform (Android, Windows, macOS)
     * @property serialNr Device serial number (optional)
     * @property swVersion Software version of the client app
     */
    @Serializable
    data class RegisterTerminalRequestDto(
        val model: String? = null,
        val brand: String? = null,
        val serialNr: String? = null,
        val swVersion: String? = null,
    )

    /**
     * Response DTO for terminal information.
     *
     * @property id Terminal database ID
     * @property deviceId Unique device identifier
     * @property terminalId Human-readable terminal ID (e.g., "T001")
     * @property terminalBrand Terminal brand/platform
     * @property terminalModel Terminal model
     * @property terminalSerialNr Terminal serial number
     * @property terminalSwBrand Software brand
     * @property terminalSwVersion Software version
     * @property isActive Terminal active status
     * @property tseClientRegistered Whether this terminal is registered with TSE hardware
     * @property registeredAt When terminal was first registered
     * @property lastSeenAt When terminal was last seen
     */
    @Serializable
    data class TerminalResponseDto(
        val id: Long,
        val deviceId: String,
        val terminalId: String,
        val terminalBrand: String?,
        val terminalModel: String?,
        val terminalSerialNr: String?,
        val terminalSwBrand: String,
        val terminalSwVersion: String?,
        val isActive: Boolean,
        val tseClientRegistered: Boolean = false,
        val registeredAt: String,
        val lastSeenAt: String,
        val role: String = "CASHIER",
        val fiscal: Boolean = true,
        // Stored since the table was created but never returned, so the back office could not show
        // which physical till a TER-00x number refers to.
        val name: String? = null,
        val location: String? = null,
        // When an unconsumed pairing code is outstanding, its expiry. The code itself is not
        // included: it is a one-time credential, and minting it already returns it once.
        val pairingCodeExpiresAt: String? = null,
        val clientKind: String = "NATIVE",
    )

    /**
     * Summary DTO for terminal list views.
     *
     * @property id Terminal database ID
     * @property terminalId Human-readable terminal ID
     * @property terminalBrand Terminal brand
     * @property terminalModel Terminal model
     * @property isActive Active status
     * @property tseClientRegistered Whether this terminal is registered with TSE hardware
     * @property lastSeenAt Last activity timestamp
     * @property bound Whether a device is currently paired to this terminal. A TSE client can only be
     *   deregistered once the terminal is deactivated AND unbound.
     */
    @Serializable
    data class TerminalSummaryDto(
        val id: Long,
        val terminalId: String,
        val terminalBrand: String?,
        val terminalModel: String?,
        val isActive: Boolean,
        val tseClientRegistered: Boolean = false,
        val lastSeenAt: String,
        val role: String = "CASHIER",
        val fiscal: Boolean = true,
        val name: String? = null,
        val location: String? = null,
        val pairingCodeExpiresAt: String? = null,
        val bound: Boolean = false,
        val clientKind: String = "NATIVE",
    )

    /**
     * Provision a new unbound terminal. Admin/manager action. `role` is a TerminalRole name; `fiscal`
     * defaults from the role when null. An absent `role` (an older client) means CASHIER; a role that
     * names no TerminalRole is refused with 400 `VALIDATION_FAILED` and a `role` field error.
     */
    @Serializable
    data class CreateTerminalRequestDto(
        val name: String? = null,
        val role: String = "CASHIER",
        val fiscal: Boolean? = null,
    )

    /** A freshly generated pairing code for an unbound/rebindable terminal. */

    /**
     * Edits a terminal's identity and role. A null field is left unchanged.
     *
     * `fiscal` is deliberately absent. Whether a terminal signs is what DSFinV-K and the TSE client
     * registration are built on; flipping it after the terminal has signed would leave receipts
     * pointing at a client that no longer claims to sign. Provision a new terminal instead.
     */
    @Serializable
    data class UpdateTerminalRequestDto(
        val name: String? = null,
        val location: String? = null,
        val role: String? = null,
    )

    /**
     * Optional body of `POST /api/v1/terminals/{id}/pairing-code`. Absent (an older client) means
     * [registerTseClient] = false: the code pairs without touching the TSE.
     */
    @Serializable
    data class PairingCodeRequestDto(
        val registerTseClient: Boolean = false,
    )

    /**
     * Whether a pairing code may carry `registerTseClient = true`: only with the TSE in
     * [TseMode.NORMAL] and commissioned ([initialized]). [available] is the server's verdict.
     */
    @Serializable
    data class TsePairingStatusDto(
        val mode: TseMode,
        val initialized: Boolean,
        val available: Boolean,
    )

    @Serializable
    data class PairingCodeResponseDto(
        val terminalDbId: Long,
        val terminalId: String,
        val code: String,
        val expiresAt: String,
        /**
         * Prüfcode of the server's TLS root certificate when it runs behind Caddy's local CA, else
         * null. Carried by the pairing QR so a till can pin that root without any CA installed.
         */
        val rootFingerprint: String? = null,
    )

    /** Device-side request to bind to a terminal using a pairing code. No JWT. */
    @Serializable
    data class PairDeviceRequestDto(
        val code: String,
        val deviceId: String,
        val model: String? = null,
        val brand: String? = null,
        val serialNr: String? = null,
        val swVersion: String? = null,
        val clientKind: String = "NATIVE",
    )

    /** Result of pairing: the terminal identity plus the issued device token (returned once). */
    @Serializable
    data class PairDeviceResponseDto(
        val terminal: TerminalResponseDto,
        val deviceToken: String,
    )

    /**
     * A station session: the access JWT a login-less ORDER_STATION runs the till shell with.
     *
     * No refresh token — renewal re-exchanges the device token. [capabilities] is the MASKED set
     * (group ∩ station mask), so the client seeds its gates without a second call. [expiresAt] is
     * the JWT's expiry as an ISO-8601 instant.
     */
    @Serializable
    data class StationSessionResponseDto(
        val accessToken: String,
        val expiresAt: String,
        val terminalId: String,
        val role: String,
        val capabilities: Set<String>,
    )
}
