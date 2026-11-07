/**
 * Immutable per-terminal capability snapshot used by the auth ceiling (Terminal Phase A4).
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.terminal.data.service

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.terminal.ClientKind

/**
 * Snapshot of the fields [TerminalCapabilityResolver] needs to gate a request: which device/token
 * it belongs to, whether the terminal is fiscal/TSE-registered/active, and its device-token expiry.
 */
data class TerminalCapability(
    val terminalDbId: Long,
    val clientId: String, // TER-xxx (the TSE client id)
    val deviceId: String?, // the PHYSICAL device id (device_id column) — what findByDeviceId matches
    val role: String,
    val clientKind: ClientKind,
    val fiscal: Boolean,
    val tseClientRegistered: Boolean,
    val isActive: Boolean,
    val tokenExpiresAt: LocalDateTime?, // for device-token expiry checks
)
