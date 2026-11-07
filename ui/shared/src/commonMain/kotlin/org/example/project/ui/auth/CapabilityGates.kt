package org.example.project.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import org.example.project.core.auth.AccessPolicy
import org.example.project.core.auth.SetAccessPolicy
import org.example.project.core.auth.TokenStore
import org.example.project.core.auth.isStation
import org.example.project.core.domain.enums.auth.Capability
import org.koin.compose.koinInject

/**
 * The capability gate for the current principal.
 *
 * Defaults to denying everything: a composable rendered outside its app's provider has no session,
 * so it must not be able to reach a privileged control. Every real screen sits inside one.
 *
 * In `shared-ui` rather than either app, so composeApp and adminApp gate through one mechanism.
 * Each app provides its own resolved [AccessPolicy] — composeApp from
 * `PermissionManager.accessPolicy`, adminApp from `TokenStore.accessPolicy` — and both go through
 * `resolveAccessPolicy` in `shared`, so the *rule* has exactly one definition.
 */
val LocalAccessPolicy: ProvidableCompositionLocal<AccessPolicy> =
    staticCompositionLocalOf { SetAccessPolicy(emptySet()) }

/**
 * Whether the current principal holds [capability] — the one question a gate should ask.
 *
 * Use this where the answer feeds something other than whether to render: an `enabled =`, or an
 * `onDelete = if (…) {…} else null`. Where it decides whether a block of UI exists at all, prefer
 * [CapabilityGate], which says so structurally.
 */
@Composable
fun holds(capability: Capability): Boolean = LocalAccessPolicy.current.allows(capability)

/**
 * Whether the principal is an unattended STATION — the client mirror of the server's `station` JWT
 * claim, hoisted out of recomposition.
 *
 * Beside [holds] because it answers a question about IDENTITY, not about navigation: who is holding
 * this device, rather than which destinations exist. It is the *complement* of [holds] — capability
 * says what may be done, this says whether there is a person to do it — and the two are read
 * together by the nav filter, the rail, the sidebar and the bottom bar.
 *
 * Read once per CALLER rather than on every recomposition: the getter is a SQLDelight SELECT on
 * composeApp, and its callers run from the containers on every notification-badge and session-timer
 * tick. Each call site memoizes its own read, so one shell render performs about three of them —
 * three SELECTs at composition instead of three per frame.
 *
 * `remember` normally freezes a non-snapshot value at the wrong moment — but the value cannot
 * change while this composition lives. The station-session exchange writes the marker before Main
 * mounts, `storeTokens` clears it only on a login (which arrives at Main from the login screen,
 * i.e. a fresh composition), and the cold reset that clears it tears the shell down. There is no
 * in-place transition to miss.
 *
 * One spelling, so the destination gate and the nav footer cannot disagree about what a station is.
 */
@Composable
fun rememberIsStation(): Boolean {
    val tokenStore = koinInject<TokenStore>()
    return remember(tokenStore) { tokenStore.isStation }
}

/**
 * Renders [content] only if the principal holds the required capabilities; renders **nothing**
 * otherwise.
 *
 * For controls *inside* a screen — a delete button, an admin section, a toolbar action. Silence is
 * the right denial here: the user is looking at a screen they may see, and a control they may not
 * use simply is not part of it.
 *
 * It is the wrong denial for a whole destination — see [RequireCapability].
 *
 * Exactly one of [anyOf] / [allOf] should be non-empty. Their empty-set semantics are inherited
 * from [AccessPolicy] and are deliberately asymmetric: an empty [anyOf] fails closed (a gate built
 * from a dynamically assembled list must not open because the list came out empty), an empty
 * [allOf] is vacuously true.
 *
 * **This hides; it does not protect.** The server enforces the same capability on the route behind
 * every one of these controls (CLAUDE.md rule 8). A gate here that is missing is a UI defect; a
 * gate on the server that is missing is a security defect.
 */
@Composable
fun CapabilityGate(
    anyOf: Set<Capability> = emptySet(),
    allOf: Set<Capability> = emptySet(),
    content: @Composable () -> Unit,
) {
    val policy = LocalAccessPolicy.current
    val allowed =
        when {
            anyOf.isNotEmpty() && allOf.isNotEmpty() ->
                policy.allowsAny(*anyOf.toTypedArray()) && policy.allowsAll(*allOf.toTypedArray())
            anyOf.isNotEmpty() -> policy.allowsAny(*anyOf.toTypedArray())
            else -> policy.allowsAll(*allOf.toTypedArray())
        }
    if (allowed) content()
}

/** Single-capability [CapabilityGate]. */
@Composable
fun CapabilityGate(
    capability: Capability,
    content: @Composable () -> Unit,
) = CapabilityGate(allOf = setOf(capability), content = content)

/**
 * A whole DESTINATION gated on a capability: renders [content] if the principal holds it, and
 * otherwise renders nothing **and calls [onDenied]** exactly once.
 *
 * The `onDenied` half is the entire reason this is a separate component from [CapabilityGate].
 * composeApp's navigation used to gate three destinations with a bare `if (role.isManagerOrHigher())
 * { Screen() }`, and a denied user landed on a **blank screen** — no message, no redirect, nothing
 * but the system back gesture. It was reachable in practice: the menu gated Financial Docs on
 * `CASHBOOK_READ` while the destination gated it on the role, so an AUDITOR — who holds the
 * capability but may carry role `CASHIER` — was shown a menu entry that led nowhere.
 *
 * Two gates for one door will always drift. Give both the same capability, and hand [onDenied] a
 * navigation action so a deep link or a stale back-stack entry lands somewhere real.
 */
@Composable
fun RequireCapability(
    capability: Capability,
    onDenied: () -> Unit,
    content: @Composable () -> Unit,
) {
    val allowed = holds(capability)
    if (allowed) {
        content()
    } else {
        LaunchedEffect(capability) { onDenied() }
    }
}
