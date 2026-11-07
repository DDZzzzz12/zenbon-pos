/**
 * Permission manager for role-based access control.
 *
 * Manages the current user's role and permissions, providing
 * methods to check permissions throughout the application.
 * Automatically extracts role and permissions from JWT access tokens.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.example.project.core.data.database.LocalDatabaseManager
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.employee.EmployeeRole

/**
 * Manages user permissions based on their role.
 *
 * Provides reactive permission checking and role management.
 * Integrates with JWT authentication and persists role information.
 * Automatically decodes JWT tokens to extract role and permissions.
 */
class PermissionManager(
    private val localDb: LocalDatabaseManager,
    private val tokenStore: TokenStore,
) {
    private val _currentRole = MutableStateFlow<EmployeeRole?>(null)
    val currentRole: StateFlow<EmployeeRole?> = _currentRole.asStateFlow()

    private val _temporaryRole = MutableStateFlow<EmployeeRole?>(null)

    private val _effectiveRole = MutableStateFlow<EmployeeRole?>(null)

    /**
     * Reactive effective role — the temporary role while a step-up is active, the JWT's role
     * otherwise.
     *
     * **Not an authorization input.** Gate on capabilities: `holds(...)`, `CapabilityGate`,
     * `RequireCapability`. This survives for two things a capability cannot express — telling a
     * login-less STATION apart from a person (`PosNavDestinationFilter`, the nav shells), and
     * picking a layout VARIANT (`PosReportsScreen`) — plus the role fallback `resolveAccessPolicy`
     * uses against a server too old to report capabilities.
     *
     * `NoRoleGatesInScreensTest` holds that line.
     */
    val effectiveRole: StateFlow<EmployeeRole?> = _effectiveRole.asStateFlow()

    /**
     * What the SERVER says this employee holds, or null if it has not said.
     *
     * Null is *unknown*, never "holds nothing" — see [accessPolicy] for what that distinction buys.
     * Capabilities deliberately do NOT come from the JWT: putting them there was defect D5, and the
     * server fixed it in Stage 2a by resolving them per request from the permission group. So they
     * arrive in the login response body and from `GET /api/auth/me`, not from the token.
     */
    private val _currentCapabilities = MutableStateFlow<Set<Capability>?>(null)

    /** The elevating principal's capabilities while a temporary session is active. */
    private val _temporaryCapabilities = MutableStateFlow<Set<Capability>?>(null)

    private val _accessPolicy = MutableStateFlow<AccessPolicy>(SetAccessPolicy(emptySet()))

    /**
     * The single question every gate should ask: does this principal hold this capability?
     *
     * Resolves in one place so no screen has to know where the answer came from:
     *  - a temporary session's capabilities win, exactly as [effectiveRole] does for roles;
     *  - otherwise the server-reported set;
     *  - and if the server reported nothing, a **role-derived fallback**.
     *
     * The fallback is not the `RoleBackedAccessPolicy` the C1 plan proposed as the primary
     * mechanism — that would reproduce the very divergence purpose-built groups exist to express.
     * It is the narrow answer to one question: what should a NEW client do against an OLD server
     * that does not report capabilities? Gating on nothing would hide every control and look like a
     * broken app. Falling back to the role reproduces today's behaviour, which is exactly right for
     * a server that still only has today's behaviour to offer.
     */
    val accessPolicy: StateFlow<AccessPolicy> = _accessPolicy.asStateFlow()

    private val _currentEmployeeNumber = MutableStateFlow<String?>(null)
    val currentEmployeeNumber: StateFlow<String?> = _currentEmployeeNumber.asStateFlow()

    private val _signedInPerson = MutableStateFlow<SignedInPerson>(SignedInPerson.Nobody)

    /**
     * Who is signed in, keyed on the token's employee id; [SignedInPerson.Nobody] after [clearRole].
     * Root-scoped ViewModels watch it (see [personLeft]) to drop what the previous person left behind.
     */
    val signedInPerson: StateFlow<SignedInPerson> = _signedInPerson.asStateFlow()

    private val _currentEmployeeName = MutableStateFlow<String?>(null)
    val currentEmployeeName: StateFlow<String?> = _currentEmployeeName.asStateFlow()

    private val _isTrainingMode = MutableStateFlow(false)

    /**
     * Whether the current user is in training mode.
     *
     * Training mode users can only access training tables/sessions,
     * and non-training users cannot access training tables/sessions.
     */
    val isTrainingMode: StateFlow<Boolean> = _isTrainingMode.asStateFlow()

    /**
     * Update the effective role based on current and temporary roles.
     *
     * Temporary role takes precedence. This method ensures the effectiveRole
     * StateFlow is updated whenever currentRole or temporaryRole changes.
     */
    private fun updateEffectiveRole() {
        _effectiveRole.value = _temporaryRole.value ?: _currentRole.value
        updateAccessPolicy()
    }

    /**
     * Recomputed whenever any input changes, so a single collector of [accessPolicy] sees every
     * transition — login, token refresh, step-up, step-down, logout.
     */
    private fun updateAccessPolicy() {
        _accessPolicy.value =
            resolveAccessPolicy(
                reported = _currentCapabilities.value,
                temporary = _temporaryCapabilities.value,
                effectiveRole = _effectiveRole.value,
            )
    }

    /**
     * Record what the server reported, mapping names to the enum and dropping any it does not know.
     *
     * Dropping unknowns is deliberate: a client meeting a server that has ADDED a capability must
     * ignore the new name, not fail. That is why the wire type is a set of strings.
     *
     * An empty or absent report is stored as null — unknown — for the reason [accessPolicy] gives.
     */
    fun setReportedCapabilities(names: Set<String>?) {
        _currentCapabilities.value = names.toCapabilitiesOrNull()
        updateAccessPolicy()
    }

    /**
     * The elevating principal's capabilities, for the life of a temporary session.
     *
     * REPLACES rather than unions, mirroring the server: `elevateWithTempSession` swaps both `role`
     * and `employeeId`, so the route plugin resolves the *elevating* principal's group and the
     * operator's own capabilities do not apply for the duration. The client must show the same set
     * the server will enforce, including where that is narrower.
     *
     * The set is fetched from `/api/auth/me` DURING elevation rather than derived from the session's
     * `managerRole`: `getTokenClaims()` elevates, so `/me` already answers as the elevated principal.
     * Deriving from the role would be wrong the moment an elevating principal sits in a
     * purpose-built group — the same class of bug this whole track removes.
     */
    fun setTemporaryCapabilities(names: Set<String>?) {
        _temporaryCapabilities.value = names.toCapabilitiesOrNull()
        updateAccessPolicy()
    }

    /** Drop the elevated set when a temporary session ends or expires. */
    fun clearTemporaryCapabilities() {
        _temporaryCapabilities.value = null
        updateAccessPolicy()
    }

    /**
     * Reads the signed-in principal's role, identity and capabilities from storage, or null when no
     * decodable access token is stored. Writes no state.
     *
     * Opens the sealed access token, which can block on the Keystore or keychain — run it on the IO
     * dispatcher, then hand the result to [applyRoleSnapshot] on the caller's context.
     */
    fun readRoleSnapshot(): RoleSnapshot? {
        val accessToken = tokenStore.accessToken
        if (accessToken.isNullOrEmpty()) return null
        val userInfo = JwtDecoder.extractUserInfo(accessToken) ?: return null
        return RoleSnapshot(
            employeeId = userInfo.employeeId,
            role = localDb.getRole(),
            employeeNumber = localDb.getEmployeeNumber(),
            employeeName = localDb.getEmployeeName(),
            trainingMode = localDb.isTrainingMode(),
            // From storage, not from the token — capabilities are deliberately not a JWT claim.
            //
            // The TokenStore is preferred over the local database because the token-refresh path
            // writes it (`/api/auth/refresh` answers a full LoginResponse), so it is the fresher of
            // the two. The database copy is the fallback that survives a process restart before any
            // refresh has happened.
            capabilities = tokenStore.capabilities ?: localDb.getCapabilities(),
        )
    }

    /** Publishes a [readRoleSnapshot] result to the role, identity and capability flows. */
    fun applyRoleSnapshot(snapshot: RoleSnapshot) {
        _currentRole.value = snapshot.role
        _currentEmployeeNumber.value = snapshot.employeeNumber
        _currentEmployeeName.value = snapshot.employeeName
        _isTrainingMode.value = snapshot.trainingMode
        _currentCapabilities.value = snapshot.capabilities.toCapabilitiesOrNull()
        updateEffectiveRole()
        // Last, so a watcher reacting to a new person already sees that person's role.
        snapshot.employeeId?.let { _signedInPerson.value = SignedInPerson.Employee(it) }
    }

    /**
     * Get the current employee role.
     *
     * @return Current role or null if not set
     */
    fun getRole(): EmployeeRole? = _currentRole.value

    /**
     * Get the effective employee role (temporary role takes precedence over base role).
     *
     * When a temporary session is active, returns the temporary elevated role.
     * Otherwise, returns the base role from the JWT token.
     *
     * @return Effective role for permission checks
     */
    fun getEffectiveRole(): EmployeeRole? = _temporaryRole.value ?: _currentRole.value

    /**
     * Record the approving principal's role for the life of a step-up session.
     *
     * **This is no longer a gate, and must not be used as one.** Since the step-up stage a session
     * UNIONS capabilities onto the operator's own set and does not change who they are — so the
     * operator is still a cashier, and an effective role of MANAGER is a statement the server would
     * not make. What they may do comes from [accessPolicy]; what they may see comes from
     * [setTemporaryCapabilities], which is filled from `GET /api/auth/me`.
     *
     * Two jobs survive, both narrow:
     *  1. **The degraded path.** `resolveAccessPolicy` falls back to `CapabilityBundles.forRole` when
     *     the server reports no capabilities at all — an older server. Without a temporary role, a
     *     step-up against such a server would appear to grant nothing.
     *  2. **Display.** Screens that show whose authority is in play while the session runs.
     *
     * @param managerRole The approving principal's role
     */
    fun setTemporaryRole(managerRole: EmployeeRole) {
        _temporaryRole.value = managerRole
        updateEffectiveRole()
    }

    /**
     * Clear the temporary elevated role.
     *
     * Reverts permission checks back to the base role from the JWT token.
     * Called when temporary session expires or is manually ended.
     * Automatically updates effectiveRole to trigger UI recomposition.
     */
    fun clearTemporaryRole() {
        _temporaryRole.value = null
        updateEffectiveRole()
    }

    /**
     * Get current employee ID if stored.
     *
     * @return Employee ID or null
     */
    fun getEmployeeId(): String? = localDb.getUserId()

    /**
     * Check if the current user has a specific role (uses effective role).
     *
     * @param role The role to check
     * @return true if user has this role
     */
    fun hasRole(role: EmployeeRole): Boolean = getEffectiveRole() == role

    /**
     * Check if the current user has any of the specified roles (uses effective role).
     *
     * @param roles The roles to check
     * @return true if user has at least one role
     */
    fun hasAnyRole(vararg roles: EmployeeRole): Boolean {
        val effectiveRole = getEffectiveRole()
        return effectiveRole != null && roles.contains(effectiveRole)
    }

    /**
     * Check if the current user is a manager or higher (uses effective role).
     *
     * @return true if user is MANAGER, ADMIN, or OWNER
     */
    fun isManagerOrHigher(): Boolean =
        hasAnyRole(
            EmployeeRole.MANAGER,
            EmployeeRole.ADMIN,
        )

    /**
     * Check if the current user is an admin (uses effective role).
     *
     * @return true if user is ADMIN
     */
    fun isAdminOrOwner(): Boolean = hasAnyRole(EmployeeRole.ADMIN)

    /**
     * Clear the current role, capabilities and permissions (logout).
     */
    fun clearRole() {
        _currentRole.value = null
        _temporaryRole.value = null
        // The capability sets go with the role. Left behind, the next principal carries the previous
        // one's set until their own load lands — a cashier logging in after a manager briefly held
        // the manager's capabilities.
        _currentCapabilities.value = null
        _temporaryCapabilities.value = null
        _isTrainingMode.value = false
        updateEffectiveRole()
        _signedInPerson.value = SignedInPerson.Nobody
    }

    /**
     * Get a summary of current permissions for debugging.
     *
     * @return String representation of current permissions
     */
    fun getPermissionsSummary(): String {
        val baseRole = _currentRole.value ?: return "No role set"
        val tempRole = _temporaryRole.value
        return if (tempRole != null) {
            "Base Role: $baseRole | Temporary Role: $tempRole"
        } else {
            "Role: $baseRole"
        }
    }
}
