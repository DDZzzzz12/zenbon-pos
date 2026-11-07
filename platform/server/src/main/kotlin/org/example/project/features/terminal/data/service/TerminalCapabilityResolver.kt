/**
 * Per-request device -> terminal-capability cache (Terminal Phase A4).
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.terminal.data.service

import org.example.project.core.domain.enums.terminal.ClientKind
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.terminal.domain.repository.TerminalRepository
import org.example.project.utils.dbQuery
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a device to its terminal's capability, cached in-memory. A DB hit on every
 * authenticated call is unacceptable, so the small capability record is cached and invalidated
 * DIRECTLY on any terminal mutation (provision/bind/rebind/revoke/tse-register/deactivate) — see
 * [org.example.project.features.terminal.data.service.TerminalServiceImpl]. A live SSE role-change
 * (Phase C) invalidates via the same [invalidate] call.
 */
class TerminalCapabilityResolver(
    private val repository: TerminalRepository,
) {
    private val byDevice = ConcurrentHashMap<String, TerminalCapability>()
    private val byTokenHash = ConcurrentHashMap<String, TerminalCapability>()

    /** deviceId -> wall-clock millis until which "no terminal row" is trusted (see [byDeviceIdForGate]). */
    private val knownMissing = ConcurrentHashMap<String, Long>()

    /** Cached lookup by the physical device id, falling back to a DB read on a cache miss. */
    suspend fun byDeviceId(deviceId: String): TerminalCapability? {
        byDevice[deviceId]?.let { return it }
        val cap = dbQuery { repository.findByDeviceId(deviceId)?.toCapability() } ?: return null
        byDevice[deviceId] = cap
        return cap
    }

    /**
     * [byDeviceId] for the per-request browser-station gate, which asks about EVERY authenticated
     * JWT request, including the many whose device has no terminal row at all (`admin-web-*`,
     * `unknown`, grandfathered ids). [byDeviceId] never caches a miss, so using it there would put
     * a database read on every such request. This variant remembers a miss for [MISSING_TTL_MILLIS]
     * (and [invalidate] forgets it at once, so a pairing takes effect immediately). It is kept
     * separate on purpose: the signing ceiling must keep reading through on every miss.
     */
    suspend fun byDeviceIdForGate(deviceId: String): TerminalCapability? {
        byDevice[deviceId]?.let { return it }
        val now = System.currentTimeMillis()
        knownMissing[deviceId]?.let { until -> if (until > now) return null }
        val cap = byDeviceId(deviceId)
        if (cap == null) knownMissing[deviceId] = now + MISSING_TTL_MILLIS else knownMissing.remove(deviceId)
        return cap
    }

    /** Cached lookup by the device-token hash, falling back to a DB read on a cache miss. */
    suspend fun byTokenHash(tokenHash: String): TerminalCapability? {
        byTokenHash[tokenHash]?.let { return it }
        val cap = dbQuery { repository.findByDeviceTokenHash(tokenHash)?.toCapability() } ?: return null
        byTokenHash[tokenHash] = cap
        return cap
    }

    /**
     * Drop any cached entry for a device, keyed on the given deviceId — both the [byDevice] entry
     * AND any [byTokenHash] entry carrying that same deviceId. Safe to call for an unknown device.
     *
     * The token-hash eviction is UNCONDITIONAL: a login-less station (ORDER_STATION/KITCHEN/etc.)
     * is cached EXCLUSIVELY via [byTokenHash] — [byDevice] is only ever populated by the JWT /
     * raw-X-Device-ID paths — so gating this on "was byDevice populated for this id" (as a prior
     * version of this method did) meant such a station's token-hash entry was NEVER evicted: a
     * revoked/deactivated/rebound login-less fiscal station kept resolving its stale, cached-valid
     * capability (and kept signing) until process restart.
     */
    fun invalidate(deviceId: String?) {
        if (deviceId == null) return
        byDevice.remove(deviceId)
        knownMissing.remove(deviceId)
        byTokenHash.entries.removeIf { it.value.deviceId == deviceId }
    }

    /** Drop the entire cache (used after a bulk change, e.g. `syncTseRegistrationStatus`). */
    fun invalidateAll() {
        byDevice.clear()
        byTokenHash.clear()
        knownMissing.clear()
    }

    private companion object {
        const val MISSING_TTL_MILLIS = 30_000L
    }

    private fun Terminal.toCapability() =
        TerminalCapability(
            terminalDbId = id.value,
            clientId = clientId,
            deviceId = deviceId,
            role = role,
            clientKind = ClientKind.fromCode(clientKind),
            fiscal = fiscal,
            tseClientRegistered = tseClientRegistered,
            isActive = isActive,
            tokenExpiresAt = deviceTokenExpiresAt,
        )
}
