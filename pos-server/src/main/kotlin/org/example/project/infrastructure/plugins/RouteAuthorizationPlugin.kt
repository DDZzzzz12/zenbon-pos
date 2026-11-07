package org.example.project.infrastructure.plugins

import com.auth0.jwt.JWT
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import org.example.project.core.auth.CapabilityBundles
import org.example.project.core.auth.StationClass
import org.example.project.core.auth.TerminalMasks
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.core.error.PosErrorCode
import org.example.project.core.security.HELD_CAPABILITIES
import org.example.project.core.security.JwtConfig
import org.example.project.core.security.MaintainerClaims
import org.example.project.core.security.TokenClaims
import org.example.project.features.auth.data.elevatedCapabilities
import org.example.project.features.employee.data.service.GroupCapabilityResolver
import org.example.project.features.maintenance.MaintenancePolicy
import org.example.project.features.terminal.data.service.BrowserStationPolicy
import org.example.project.features.terminal.data.service.DeviceTokenGenerator
import org.example.project.features.terminal.data.service.TerminalCapability
import org.example.project.features.terminal.data.service.TerminalCapabilityResolver
import org.example.project.features.terminal.domain.routes.DEVICE_ID_HEADER
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.security.AccessDecision
import org.example.project.infrastructure.security.AccessLevel.*
import org.example.project.infrastructure.security.RoutePermissions
import org.example.project.infrastructure.security.TransactionalRoutes
import org.example.project.utils.formatter.currentTimeStamp
import org.koin.ktor.ext.getKoin
import org.slf4j.LoggerFactory
import java.util.UUID

private val logger = LoggerFactory.getLogger("RouteAuthorization")

private const val ME_BY_DEVICE_PATH = "/api/v1/terminals/me-by-device"
private const val LOGOUT_PATH = "/api/auth/logout"
private const val NOTIFICATION_STREAM_PATH = "/api/notifications/stream"

/**
 * Routes a DISABLED browser station keeps, so it can still learn why it is locked out and sign out:
 * `me-by-device` (its own state), `logout`, and the notification stream.
 *
 * The stream is exempt because refusing it can race the kick. `PUT .../browser-stations` commits and
 * refreshes the configuration cache (which is what makes the policy refuse) BEFORE
 * `TerminalServiceImpl.onBrowserStationRolesChanged` has read the affected terminals and pushed
 * `terminal_session_terminated(BROWSER_DISABLED)`. A station that reconnects its stream inside that
 * window would be refused, never receive the push, and the client acts on this code only from the
 * push and the launch flow, not from a bare 403 on the stream, so it would retry forever without
 * being told. Letting the stream open costs nothing new: that JWT could read it until expiry before
 * this gate existed, and an already-open connection is kicked by the push. Refresh is `Public`
 * and is refused separately by the auth route.
 */
private fun isBrowserGateExempt(
    path: String,
    method: HttpMethod,
): Boolean =
    (path == ME_BY_DEVICE_PATH && method == HttpMethod.Get) ||
        (path == LOGOUT_PATH && method == HttpMethod.Post) ||
        (path == NOTIFICATION_STREAM_PATH && method == HttpMethod.Get)

/**
 * Installs the [RouteAuthorizationPlugin] to enforce centralized route permissions.
 * Must be called after [configureSecurity] so JWT validation is available, and after
 * `configureKoin()` so the plugin can resolve [TerminalCapabilityResolver] /
 * [IShopConfigurationManager] from the DI container at install time.
 */
fun Application.configureAccessControl() {
    install(RouteAuthorizationPlugin)
}

/**
 * Application plugin for centralized route authorization.
 *
 * Consults [RoutePermissions] registry to enforce access rules on every API request. For the
 * exact transactional routes named in [TransactionalRoutes] it additionally enforces the
 * terminal-capability ceiling (the "station" dimension): a non-fiscal terminal can never sign,
 * and a fiscal-but-unregistered terminal is refused while TSE mode is NORMAL.
 *
 * Uses [JwtConfig.validateToken] for JWT decoding since `call.principal()` is scoped
 * to route-level `authenticate()` blocks and unavailable in application-level plugins.
 * Unregistered routes are denied by default.
 */
val RouteAuthorizationPlugin =
    createApplicationPlugin(name = "RouteAuthorization") {
        // Resolved once, at plugin-install time (configureAccessControl runs after configureKoin —
        // see ServerManager's Application.module — so the DI container is already populated).
        val capabilityResolver = application.getKoin().get<TerminalCapabilityResolver>()
        val configManager = application.getKoin().get<IShopConfigurationManager>()
        val groupResolver = application.getKoin().get<GroupCapabilityResolver>()
        val browserStationPolicy = application.getKoin().get<BrowserStationPolicy>()

        onCall { call ->
            val path = call.request.path()

            // Skip non-API paths (static resources, swagger, manual, health, etc.)
            if (!path.startsWith("/api")) return@onCall

            val method = call.request.httpMethod

            // Maintainer containment, layer 3 (spec §5.4). The maintainer token opens ONE kind of
            // route. Layer 1 (JwtConfig.validateToken answers null for it) already makes every
            // existing decoder blind to it; this makes the refusal explicit and uniform — 403 — on
            // every rule that is neither Maintainer nor Public, including transactional routes, so an
            // unforeseen decoding path cannot turn into access. Any token that merely claims the
            // maintainer type is refused, verified or not (fail closed; see presentsMaintainerToken).
            val rule = RoutePermissions.findRule(path, method)
            if (rule != null && rule !is Maintainer && rule !is Public && call.presentsMaintainerToken()) {
                throw UnauthorizedException("The maintainer account cannot call this route")
            }

            // --- Terminal-capability ceiling (station dimension) ---
            // Runs BEFORE the RoutePermissions check below (and therefore before the ADMIN
            // short-circuit inside it) so it cannot be bypassed by an admin role claim — only the
            // exact set of routes that reach a TSE signTransaction/enqueue call are gated here.
            if (TransactionalRoutes.isTransactional(path, method)) {
                // Resolve from the TRUSTED identity (same as signing), NOT the spoofable raw
                // X-Device-ID: 1) device token (X-Device-Token) — the login-less station's
                // credential; 2) else the signed JWT deviceId claim (decoded from Authorization,
                // since the principal is not set in this pre-routing onCall phase); 3) else raw
                // X-Device-ID (grandfather fallback for pre-pairing field devices only).
                val cap = resolveCeilingCapability(call, capabilityResolver)
                if (cap == null) {
                    throw ceilingRefusal(PosErrorCode.DEVICE_NOT_PAIRED, HttpStatusCode.Conflict, "Device not paired")
                }
                // A BROWSER terminal whose role the shop has switched off acts nowhere on a
                // transactional route, whichever credential identified it (device token, station
                // JWT's deviceId claim, raw header). Pairing, login and refresh already refuse it;
                // this closes the window where a stale token keeps working. NATIVE terminals and
                // unknown devices pass untouched. The policy reads the cached shop configuration.
                browserStationPolicy.requireAllowed(cap.clientKind, TerminalRole.fromCode(cap.role))
                if (!cap.isActive) {
                    throw ceilingRefusal(PosErrorCode.TERMINAL_DEACTIVATED, HttpStatusCode.Forbidden, "Terminal is deactivated")
                }
                if (!cap.fiscal) {
                    throw ceilingRefusal(
                        PosErrorCode.TERMINAL_NOT_FISCAL,
                        HttpStatusCode.Forbidden,
                        "Non-fiscal terminal cannot perform transactions",
                    )
                }
                // A registered TSE client is required only when signing to hardware (NORMAL).
                // SIMULATION mock-signs (no registered client needed); DISABLED does not sign.
                // Fail CLOSED on a config-read failure: default to NORMAL rather than `getOrNull()`
                // silently skipping the registered-client requirement below if the true mode is
                // NORMAL (fail-open would let an unregistered fiscal terminal sign to hardware).
                val mode = runCatching { configManager.getTseMode() }.getOrDefault(TseMode.NORMAL)
                if (mode == TseMode.NORMAL && !cap.tseClientRegistered) {
                    throw ceilingRefusal(
                        PosErrorCode.TERMINAL_NOT_TSE_REGISTERED,
                        HttpStatusCode.FailedDependency,
                        "Terminal not TSE-registered",
                    )
                }
            }

            when (val accessLevel = rule) {
                null -> {
                    logger.warn("No permission rule for ${method.value} $path — denied")
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Access denied"))
                    return@onCall
                }
                is Public -> { /* no check needed */ }
                is Maintainer -> {
                    // 404, not 401: a switched-off feature does not announce that it exists.
                    if (!MaintenancePolicy.enabled) {
                        call.respond(HttpStatusCode.NotFound)
                        return@onCall
                    }
                    val maintainer = call.verifiedMaintainerClaims()
                    if (maintainer == null) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                        return@onCall
                    }
                    call.attributes.put(maintainerVerifiedKey, maintainer)
                }
                // One branch for every arm that names capabilities. They differ only in how
                // `AccessDecision.check` combines the names — `Cap` one, `CapWithOwnership` any one
                // of, `AllOf` all of — and that comparison happens at the bottom of this block. What
                // the caller HOLDS is resolved identically for all three, so splitting them here
                // would duplicate the group lookup, the station mask and the elevation union, which
                // is exactly how two paths come to disagree about the same principal.
                is Cap, is CapWithOwnership, is AllOf -> {
                    // Must decode the JWT directly here. `call.getTokenClaims()` relies on
                    // `principal<JWTPrincipal>()`, which is only populated after route-level
                    // `authenticate("auth-jwt")` runs — this plugin executes in `onCall`, BEFORE
                    // routing, so the principal is always null here. Falling back to the
                    // Authorization header decodes and validates the token with the same signing
                    // key, then enforces access exactly as `RoutePermissions` declares.
                    val claims = call.resolveClaimsForAccessControl()

                    // A bearer that WAS presented but does not verify is answered the way the
                    // `auth-jwt` challenge answers it — 401 — and is never re-identified as the
                    // station below. A paired till keeps sending its X-Device-Token next to the
                    // person's JWT, so once the access token expired the fallback resolved the
                    // person's request as the login-less device and answered `403 Required
                    // capability` for a capability the person plainly holds. No client refreshes
                    // on a 403; only the 401 starts the token refresh (2026-09-11 rehearsal: the
                    // admin was locked out of the Z-Bon until reload and re-login).
                    if (claims == null) call.requireNoUnverifiableBearer()

                    // A login-less station authenticates with X-Device-Token and carries NO JWT, so
                    // `claims` is null for it. Before Stage 1 these routes were `Authenticated`,
                    // which the plugin treated as a no-op and left entirely to Ktor's route-level
                    // `device-principal` provider; `Cap` is not a no-op, so the station must be
                    // recognised here or every login-less order 401s.
                    //
                    // A usable device token — active terminal, not expired — resolves to the
                    // TERMINAL_DEVICE bundle, narrowed by its station mask further down.
                    // Attribution to the terminal-employee still happens in the handler via
                    // `getDeviceTokenClaims`; this only decides reachability.
                    val station = if (claims == null) call.usableDeviceStation(capabilityResolver) else null

                    // A disabled browser station must not act on its device token alone. The one
                    // route it keeps is `GET /api/v1/terminals/me-by-device`, so it can still read
                    // its own state and tell the operator why it is locked out (the live kick rides
                    // `/api/notifications/stream`, which needs a JWT and so never reaches this
                    // device-token branch; pairing/login/refresh are gated separately). JWTs are
                    // checked right below, on every route, not only the transactional ones.
                    if (station != null && !isBrowserGateExempt(path, method)) {
                        browserStationPolicy.requireAllowed(station.clientKind, TerminalRole.fromCode(station.role))
                    }
                    // The same rule for a JWT: a station JWT or a browser cashier's person JWT
                    // minted before the role was disabled is refused on every route, not only the
                    // transactional ones. The terminal comes from the signed `deviceId` claim via
                    // the cached resolver; a device with no terminal row (admin-web-*, "unknown")
                    // and NATIVE terminals pass untouched.
                    if (claims != null && !isBrowserGateExempt(path, method)) {
                        claims.deviceId.takeIf { it.isNotBlank() && it != "unknown" }?.let { did ->
                            capabilityResolver.byDeviceIdForGate(did)?.let { cap ->
                                browserStationPolicy.requireAllowed(cap.clientKind, TerminalRole.fromCode(cap.role))
                            }
                        }
                    }
                    val role =
                        when {
                            claims != null -> EmployeeRole.getRole(claims.role)
                            station != null -> EmployeeRole.TERMINAL_DEVICE
                            else -> {
                                logger.warn("Unauthenticated call to capability-protected route ${method.value} $path")
                                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
                                return@onCall
                            }
                        }

                    // The permission GROUP is the authority as of Stage 2a; `role` survives only
                    // for the JWT claim and client display.
                    //
                    // A device-token principal carries no employee id, so it keeps resolving through
                    // the bundle for TERMINAL_DEVICE — a login-less station has no employee row to
                    // belong to a group.
                    //
                    // ADMIN needs no short-circuit: its group is total by construction, which is what
                    // makes admin's reach auditable in Stage 5 rather than hidden in a branch here.
                    val employeeId =
                        claims?.employeeId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    val identityHeld =
                        employeeId?.let { groupResolver.forEmployee(it) }
                            // A login-less station has no employee row, so it resolves by GROUP CODE
                            // instead — the role's group, read from the same table everyone else uses.
                            //
                            // This used to read `CapabilityBundles.forRole(role)`, which made the
                            // database the authority for people and the compiled bundle the authority
                            // for stations. Harmless while nobody could edit a group; wrong the moment
                            // Stage 5 lets them, because narrowing TERMINAL_DEVICE would silently not
                            // apply to a single station.
                            //
                            // Fails CLOSED if the group row is missing (the resolver logs it at ERROR).
                            // Falling back to the bundle is what let the two authorities diverge, and
                            // seeding plus V110-V113 make a missing row a broken database, not a case
                            // to paper over.
                            ?: groupResolver.forGroupCode(role.name)
                            ?: emptySet()

                    // Stage 4 — the station axis. Two ways a request proves it comes from a station,
                    // both server-side facts: a device token that matched a terminal row, or a
                    // signed JWT carrying the `station` claim, which only the station-session
                    // exchange mints (order-station shell, 2026-09-06). A person's JWT carries no
                    // claim and passes UNBOUND, whose mask is the full set — deliberately, so the
                    // till's step-up flow stays governed by the identity axis.
                    //
                    // The two proofs are not equally live. On `TransactionalRoutes`, the terminal
                    // ceiling (unpaired 409 / deactivated 403 / non-fiscal 403 / TSE-unregistered 424)
                    // runs per request for BOTH — it resolves the terminal from the physical device id
                    // the token carries, station JWT included. Everywhere else, only the device-token
                    // path re-checks `isActive` per request (via `usableDeviceStation`); a station JWT
                    // does not, so it keeps authorizing plain reads for up to
                    // `security.jwt.accessTokenExpiryMinutes` (15 min default) after the terminal is
                    // deactivated, until its next re-exchange fails at the ceiling. Accepted and
                    // bounded, not an oversight.
                    //
                    // The mask deliberately does NOT look up a person's JWT's device: a person keeps
                    // their identity mask wherever they log in, and step-up governs the till. The
                    // billing and line-pricing rules do not read the station either: they key on who
                    // the request is attributed to (`BillingAuthority.mayBillAs`,
                    // `PriceAuthority.mayPriceLinesAs`), so a person's own login on a station device
                    // bills and prices as that person, and the station account never does.
                    val stationClass =
                        StationClass.forRequest(
                            deviceTerminalRole = station?.role,
                            fallback = StationClass.fromTerminalRole(claims?.station),
                        )
                    val masked = TerminalMasks.apply(identityHeld, stationClass)

                    // Step-up (temporary session), applied as a UNION on top of what this employee
                    // already holds — never as a replacement of their identity.
                    //
                    // Applied AFTER the station mask on purpose: a manager standing at a kitchen
                    // display lending BILL_VOID is lending an authority the station cannot exercise,
                    // and masking it away afterwards would make the elevation silently do nothing.
                    // Elevation answers "may this person do it"; the mask answers "may it be done
                    // from here". They are different questions and the mask is not the one being
                    // overridden — every lendable capability is a till override, and TILL is the
                    // one station class whose mask is full anyway.
                    val lent = claims?.elevatedCapabilities().orEmpty()
                    val elevated = masked + lent

                    // Development-mode IMPERSONATION, and only when the header names a real group.
                    // Applied last so it replaces the resolved answer outright: the point is to see
                    // the app exactly as that group sees it, which a union or an intersection with
                    // the dev user's own (ADMIN) capabilities would defeat.
                    //
                    // The gate below still runs. This chooses the principal; it does not skip the
                    // check — see DevImpersonation for why that distinction is the whole design.
                    val held = call.devImpersonatedCapabilities() ?: elevated

                    // Shadow comparison — REMOVE ONE RELEASE AFTER CUTOVER. 2a swaps the resolver and
                    // introduces a cache in the same change, and cache-invalidation bugs are exactly
                    // the class that passes every test and fails in production under concurrency.
                    //
                    // Since Stage 3 an employee may deliberately sit in a group that is not their
                    // role's — an accountant carries role MANAGER and group ACCOUNTANT — so for them
                    // divergence is the design, not a symptom, and comparing would warn on every
                    // request. Skip those; the check keeps its value for everyone else, which is who
                    // it was written for.
                    if (employeeId != null) {
                        // Compares the IDENTITY set, not the masked one: the station axis is a
                        // deliberate Stage 4 narrowing, not a 2a cutover discrepancy, and folding it
                        // in here would make every masked station look like a cache bug.
                        val fromBundle = CapabilityBundles.forRole(role)
                        val inOwnRoleGroup = groupResolver.groupCodeFor(employeeId) == role.name
                        if (identityHeld != fromBundle && inOwnRoleGroup) {
                            logger.warn(
                                "SHADOW: group/bundle disagree for {} {} employee={} onlyInGroup={} onlyInBundle={}",
                                method.value,
                                path,
                                employeeId,
                                identityHeld - fromBundle,
                                fromBundle - identityHeld,
                            )
                        }
                    }
                    // Publish the resolution for the handler.
                    //
                    // A `CapWithOwnership` route still has to prove ownership, and it must do so
                    // against the SAME answer the plugin just computed. Re-resolving in the handler
                    // would be a second opinion about what the caller holds, and a second opinion is
                    // how the client and server hierarchies drifted apart in defect D2.
                    call.attributes.put(HELD_CAPABILITIES, held)

                    // The comparison itself lives in `AccessDecision`, shared with
                    // `/api/admin/permissions/explain`. A diagnostic that reimplemented this rule
                    // would eventually disagree with the gate, and it is consulted precisely when
                    // somebody is already confused.
                    AccessDecision.check(accessLevel, held)?.let { throw UnauthorizedException(it.reason) }
                }
            }
        }
    }

private suspend fun ApplicationCall.resolveClaimsForAccessControl(): TokenClaims? {
    // Try the principal path first — works in dev mode (which synthesizes claims from a
    // UserIdPrincipal) and leaves room for future `onCallRespond`-phase callers.
    getTokenClaims()?.let { return it }

    // Fallback for the `onCall` phase: decode the Authorization header directly.
    val token = bearerToken() ?: return null
    val claims = JwtConfig.validateToken(token) ?: return null

    // Stash the verified claims so route handlers reuse them instead of verifying the same token a
    // second time. Stage 1 turned 181 previously-`Authenticated` rules into `Cap`, so this decode
    // now runs on the POS hot path — catalog sync, table sync, order creation — where paying for
    // two HMAC verifications per request is pure waste.
    attributes.put(verifiedClaimsKey, claims)
    return claims
}

/**
 * Refuses a presented `Authorization: Bearer` token that failed verification with a 401 —
 * `AUTH_SESSION_EXPIRED` when it expired, `AUTH_INVALID_CREDENTIALS` otherwise — so the client's
 * token refresh runs (the Ktor Auth plugin refreshes on the status, and StatusPages shapes every
 * 401 into a problem response; the `auth-jwt` challenge in Security.kt ends up the same way).
 * A request without a bearer — the login-less station's — passes through untouched.
 *
 * Dev mode never arrives here: `getTokenClaims` synthesizes the dev principal's claims before the
 * header is ever decoded, so `claims` is non-null and this is not consulted.
 */
private fun ApplicationCall.requireNoUnverifiableBearer() {
    val token = bearerToken() ?: return
    if (JwtConfig.isTokenExpired(token)) {
        throw PosException(
            PosErrorCode.AUTH_SESSION_EXPIRED,
            HttpStatusCode.Unauthorized,
            "Access token has expired — refresh required",
        )
    }
    throw PosException(
        PosErrorCode.AUTH_INVALID_CREDENTIALS,
        HttpStatusCode.Unauthorized,
        "Invalid authentication token",
    )
}

private fun ApplicationCall.bearerToken(): String? = JwtConfig.extractBearerToken(request.header("Authorization"))

/**
 * True when the presented bearer CLAIMS to be a maintainer token — read WITHOUT verifying it.
 *
 * Deliberately unverified: this runs on every bearer-carrying `/api` request, and the guard it feeds
 * only ever refuses, so a second HMAC check here would buy nothing. It fails closed — a forged or
 * expired maintainer-typed token is refused with the same 403 as a genuine one. The `Maintainer` rule
 * branch, which GRANTS access, verifies through [verifiedMaintainerClaims].
 */
private fun ApplicationCall.presentsMaintainerToken(): Boolean {
    val token = bearerToken() ?: return false
    return runCatching { JWT.decode(token).getClaim("tokenType").asString() }.getOrNull() == JwtConfig.MAINTAINER_TOKEN_TYPE
}

/**
 * The maintainer's claims, or null. Also honours the session cutoff ("revoke all sessions"): a token
 * issued before it is refused, exactly as the `auth-jwt` provider refuses an employee's.
 */
private fun ApplicationCall.verifiedMaintainerClaims(): MaintainerClaims? {
    val claims = bearerToken()?.let { JwtConfig.validateMaintainerToken(it) } ?: return null
    val cutoff = ShopConfigurationManager.effectiveSessionCutoffEpochSeconds()
    return claims.takeIf { (it.issuedAt?.epochSecond ?: 0L) >= cutoff }
}

/**
 * The terminal this request's device token proves, or null if it proves none — one belonging to an
 * ACTIVE terminal whose token has not expired.
 *
 * Both conditions mirror [DeviceTokenAuthenticationProvider] deliberately. A gate here that is
 * weaker than the auth provider is only harmless while every `Cap` route also sits behind an
 * `authenticate` block; the moment one does not, the weaker check becomes the whole gate.
 *
 * Returns the capability rather than a boolean since Stage 4: the station axis needs the terminal's
 * ROLE, and re-reading it would mean a second lookup that could disagree with the one that just
 * authorised the call.
 *
 * The resolver caches by token hash and is invalidated on every terminal mutation, so this usually
 * costs no database round-trip — but it does read through on a cache miss.
 */
private suspend fun ApplicationCall.usableDeviceStation(resolver: TerminalCapabilityResolver): TerminalCapability? {
    val token = request.header("X-Device-Token")?.takeIf { it.isNotBlank() } ?: return null
    val cap = resolver.byTokenHash(DeviceTokenGenerator.hash(token)) ?: return null
    if (!cap.isActive) return null
    val expiresAt = cap.tokenExpiresAt
    return cap.takeIf { expiresAt == null || expiresAt >= currentTimeStamp() }
}

/**
 * Resolves the request's TRUSTED device identity for the terminal-capability ceiling — never
 * the spoofable raw [DEVICE_ID_HEADER] alone. Priority, each cryptographically bound identity
 * winning over the next:
 *  1. `X-Device-Token` — the login-less station's credential, matched by its stored hash;
 *  2. the signed JWT `deviceId` claim, decoded directly from the `Authorization` header since
 *     the principal is not populated yet at this pre-routing `onCall` phase;
 *  3. the raw `X-Device-ID` header — a grandfather fallback for pre-pairing/legacy field devices.
 *
 * If identity #1 or #2 resolves to a KNOWN (but possibly non-fiscal) terminal, that capability is
 * returned immediately — a raw `X-Device-ID` naming a different, more privileged terminal is never
 * consulted. This is what stops a spoofed header from upgrading a non-fiscal station.
 */
private suspend fun resolveCeilingCapability(
    call: ApplicationCall,
    resolver: TerminalCapabilityResolver,
): TerminalCapability? {
    // 1) device token — the login-less station's credential.
    call.request.header("X-Device-Token")?.takeIf { it.isNotBlank() }?.let { tok ->
        resolver.byTokenHash(DeviceTokenGenerator.hash(tok))?.let { return it }
    }

    // 2) signed JWT deviceId claim — decode the Authorization header directly since the
    // principal is not set yet in this pre-routing onCall phase.
    val auth = call.request.header("Authorization")
    if (auth != null && auth.startsWith("Bearer ", ignoreCase = true)) {
        val token = auth.substring("Bearer ".length).trim()
        JwtConfig.validateToken(token)?.deviceId?.takeIf { it.isNotBlank() && it != "unknown" }?.let { did ->
            resolver.byDeviceId(did)?.let { return it }
        }
    }

    // 3) grandfather fallback: raw X-Device-ID (pre-pairing/legacy field devices only).
    call.request.header(DEVICE_ID_HEADER)?.takeIf { it.isNotBlank() }?.let { did ->
        resolver.byDeviceId(did)?.let { return it }
    }

    return null
}

/**
 * A terminal-ceiling refusal, thrown so StatusPages writes it as application/problem+json with a
 * typed code and the request's traceId like every other error (E2E-2026-09-18 #15). It used to
 * answer `{"error": …}`, and a plain 403 was even rewritten to a detail-less AUTH_FORBIDDEN. The
 * statuses (409/403/403/424) and English details are unchanged: the till routes these by status.
 */
private fun ceilingRefusal(
    code: PosErrorCode,
    status: HttpStatusCode,
    detail: String,
) = PosException(code, status, detail)
