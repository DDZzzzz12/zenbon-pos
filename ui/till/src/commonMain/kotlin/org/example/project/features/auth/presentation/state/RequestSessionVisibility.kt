package org.example.project.features.auth.presentation.state

import org.example.project.core.auth.AccessPolicy
import org.example.project.core.auth.ElevationScope

/**
 * Whether a nav shell offers the "request session" button — the generic manager-override entry.
 *
 * Design §3.2: offered whenever no temporary session is active and the principal lacks at least
 * one of [ElevationScope.TILL_OVERRIDES]. A manager holds all of them, a low-privilege person some.
 * Not a role question: a person whose group is TERMINAL_DEVICE keeps the button because their
 * capability set says so, and a cashier in a narrowed group gains it for the same reason. The
 * TERMINAL_DEVICE role literal this replaces (order-station shell, 2026-09-06) would have hidden it
 * from both.
 *
 * **On a station it is the manager sign-in** ([isStation], i.e. `TokenStore.isStation` — the
 * station-session marker, not a role). Owner decision 2026-10-03 (emu21 NOTE-v): a station never
 * requests a Storno approval; instead a manager signs in there with number + PIN and performs the
 * Storno or the transfer. The station is offered the button while it lacks one of
 * [ElevationScope.STATION_OVERRIDES] — the only things a station sign-in lends — and never for the
 * till overrides it could not be lent anyway (it never bills or prices). This replaces NOTE-10's
 * "never on a station", which the server then enforced with SELF_WRITE; the server now admits the
 * station's own JWT and scopes the lend.
 *
 * A null receiver is a shell with no session manager and offers nothing. Pass [policy] from
 * `LocalAccessPolicy.current` — the same local `holds()` reads — so the button leaves when a
 * step-up starts and returns when it ends.
 */
fun TemporarySessionUiState?.offersRequestSession(
    policy: AccessPolicy,
    isStation: Boolean,
): Boolean {
    val state = this ?: return false
    if (state.hasActiveSession) return false
    val lendable = if (isStation) ElevationScope.STATION_OVERRIDES else ElevationScope.TILL_OVERRIDES
    return lendable.any { !policy.allows(it) }
}
