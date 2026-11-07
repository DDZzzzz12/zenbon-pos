@file:OptIn(kotlin.time.ExperimentalTime::class)

package org.example.project.features.auth.data

import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.security.TokenClaims

/**
 * The capabilities an active step-up lends to [employeeId] on [deviceId], or the empty set.
 *
 * **Replaces `elevateWithTempSession()`**, which rewrote the token's `employeeId` and `role` to the
 * approving manager's. Three things were wrong with that:
 *
 * 1. It granted the manager's ENTIRE reach for three minutes, not the one override the cashier was
 *    blocked on.
 * 2. Being a replacement, it could also take capabilities AWAY — the manager's own restrictions
 *    applied instead of the cashier's.
 * 3. Every action in the window was attributed to the manager. The audit trail said a manager voided
 *    the bill; a cashier did, with a manager standing beside them. For a Kassennachschau those are
 *    not the same statement.
 *
 * **Both the device and the employee must match.** The old lookup was by device alone, so any
 * request arriving from that till — a second cashier, a queued background call — inherited the
 * elevation. The session is granted to a person at a station, and is only honoured for that person.
 *
 * The lookup itself lives in [TemporarySessionStore.activeElevationFor] rather than here, because
 * `ExceptionConverter` (core-data) asks the same question when it stamps `authorization_type` on the
 * audit row. Two copies could drift, and the trail would then claim an elevation this gate never
 * honoured — or stay silent about one it did.
 */
fun TokenClaims.elevatedCapabilities(): Set<Capability> =
    TemporarySessionStore
        .activeElevationFor(employeeId = employeeId, deviceId = deviceId)
        ?.grantedCapabilities
        ?: emptySet()

/**
 * Who a manager-override action (item Storno, item/table transfer, session delete) is attributed
 * to: the caller, except on an ORDER STATION under a manager's temporary sign-in, where it is the
 * MANAGER.
 *
 * Owner decision 2026-10-03 (emu21 NOTE-v). A station is login-less and shared — the helper tapping
 * it has no account of their own, and the station account is not a person. When a manager signs
 * in there with number + PIN, the manager is the one performing the correction, so the audit row,
 * the cancellation/transfer record and the TSE transaction carry the manager. At a till the
 * cashier stays the actor and the manager is only recorded as the authoriser — see the KDoc on
 * [elevatedCapabilities] for why that rule is right *there*.
 *
 * Keyed exactly like [elevatedCapabilities] (station device AND station account), so it cannot
 * attach to a session granted to somebody else; it ends with the session — afterwards the station
 * account is the actor again, and lacks the capability anyway.
 */
fun TokenClaims.overrideActorId(): String {
    if (station == null) return employeeId
    return TemporarySessionStore
        .activeElevationFor(employeeId = employeeId, deviceId = deviceId)
        ?.managerId
        ?: employeeId
}
