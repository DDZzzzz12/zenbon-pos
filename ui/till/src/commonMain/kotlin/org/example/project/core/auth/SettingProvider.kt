/**
 * Permission provider for composition-based permission access.
 *
 * Provides PermissionManager throughout the composition tree and automatically
 * loads permissions from JWT access token after login.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.auth

import androidx.compose.runtime.*
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.utils.LocalDisplayCurrency
import org.example.project.features.auth.presentation.viewmodels.TemporarySessionManager
import org.example.project.ui.auth.LocalAccessPolicy
import org.koin.compose.koinInject

/**
 * Local composition for PermissionManager.
 *
 * Provides access to permission checking throughout the composition tree.
 */
val LocalPermissionManager =
    compositionLocalOf<PermissionManager> {
        error("PermissionManager not provided. Wrap your app with SettingProvider.")
    }

val LocalSettingManager =
    compositionLocalOf<SettingManager> {
        error("SettingManager not provided. Wrap your app with SettingProvider.")
    }

/**
 * Local composition for TemporarySessionManager.
 *
 * Provides access to temporary session tracking for privileged operations.
 * Used to automatically extend sessions when manager-level operations are performed.
 */
val LocalTemporarySessionManager =
    compositionLocalOf<TemporarySessionManager?> {
        null // Optional - returns null if not provided (e.g., when not logged in)
    }

/**
 * Provider composable for permission context.
 *
 * Automatically loads permissions from JWT access token and provides
 * PermissionManager throughout the composition tree for permission checks.
 *
 * Usage:
 * ```kotlin
 * @Composable
 * fun App() {
 *     SettingProvider {
 *         MainScreen()
 *     }
 * }
 *
 * @Composable
 * fun MainScreen() {
 *     // Gate on capabilities. `holds` reads the policy the server resolved for this principal.
 *     if (holds(Capability.ORDER_WRITE)) {
 *         EditButton()
 *     }
 * }
 * ```
 *
 * @param content The composable content that needs permission access
 */

/*
 * `holds` and `LocalAccessPolicy` moved to `shared-ui` (`org.example.project.ui.auth`), together
 * with `CapabilityGate` and `RequireCapability`. One gating mechanism for both clients: adminApp
 * had its own, and two implementations of one rule is how the client and server hierarchies drifted
 * apart in the first place.
 *
 * `SettingProvider` below still PROVIDES the policy — the value is composeApp's to resolve — but no
 * longer defines the local or the reader.
 */

@Composable
fun SettingProvider(
    isAuthenticated: Boolean,
    syncComplete: Boolean,
    content: @Composable () -> Unit,
) {
    val permissionManager: PermissionManager = koinInject()
    val settingManager: SettingManager = koinInject()
    val sessionRoleLoader: SessionRoleLoader = koinInject()

    // Load permissions from token on first composition. The loader opens the sealed access token off the UI thread and
    // publishes the role back here only while the session is still authenticated: a logout does not cancel this effect
    // until the next recomposition, so a read that returns after it must not put the role back.
    LaunchedEffect(isAuthenticated) {
        if (isAuthenticated) sessionRoleLoader.load()
    }

    LaunchedEffect(syncComplete) {
        if (syncComplete && isAuthenticated) {
            settingManager.loadSetting()
        }
    }

    // The gate every screen will ask, once slices 2 and 3 move the call sites onto it. Provided
    // now, unused, so the plumbing lands in a reviewable change of its own rather than buried in a
    // sweep of 150 call sites.
    val accessPolicy by permissionManager.accessPolicy.collectAsState()

    // The currency the shared-ui money formatters read. They used to reach into LocalSettingManager
    // themselves, which kept them in this module; a plain string is all they need, and providing it
    // here means every existing call site keeps the same value from the same source.
    val displayCurrency by settingManager.defaultCurrency.collectAsState()

    CompositionLocalProvider(
        LocalPermissionManager provides permissionManager,
        LocalSettingManager provides settingManager,
        LocalAccessPolicy provides accessPolicy,
        LocalDisplayCurrency provides displayCurrency,
    ) {
        content()
    }
}

/**
 * Get current employee role from composition.
 *
 * Returns the current role as a State that automatically updates
 * when permissions change.
 *
 * Usage:
 * ```kotlin
 * @Composable
 * fun MyScreen() {
 *     val role by rememberRole()
 *     when (role) {
 *         EmployeeRole.MANAGER -> ManagerView()
 *         EmployeeRole.CASHIER -> CashierView()
 *         else -> BasicView()
 *     }
 * }
 * ```
 *
 * @return State containing current EmployeeRole or null
 */
@Composable
fun rememberRole(): State<EmployeeRole?> {
    val permissionManager = LocalPermissionManager.current
    return permissionManager.effectiveRole.collectAsState()
}

/*
 * The Compose permission helpers that lived here — `rememberPermissions`, `hasPermission`,
 * `hasAnyPermission`, `hasAllPermissions`, and the `WithPermission` / `WithAnyPermission` /
 * `WithAllPermissions` / `PermissionGate` wrappers built on them — are gone.
 *
 * None had a call site, and none could ever return true: they read the JWT's `permissions` claim,
 * which Stage 2a emptied permanently when it retired per-employee grants. Their KDoc examples named
 * values (`VIEW_REPORTS`, `EDIT_ORDERS`, `MANAGE_INVENTORY`) that the enum never contained, which is
 * how long they had been decorative. The enum itself is gone now — the JWT claim, `TokenClaims`, the
 * DTO field and the client flow with it.
 *
 * Gate UI on capabilities instead — `LocalPermissionManager.current.currentCapabilities`, or the
 * named helpers beside it.
 */

/**
 * Check if user has a specific role from composition.
 *
 * Usage:
 * ```kotlin
 * @Composable
 * fun SettingsButton() {
 *     val isManagerOrHigher by hasRole(EmployeeRole.MANAGER)
 *     if (isManagerOrHigher) {
 *         IconButton(onClick = { /* ... */ }) {
 *             Icon(Icons.Default.Settings, "Settings")
 *         }
 *     }
 * }
 * ```
 *
 * @param role The role to check
 * @return State containing true if user has this role
 */
@Composable
fun hasRole(role: EmployeeRole): State<Boolean> {
    val currentRole by rememberRole()
    return remember(currentRole, role) {
        derivedStateOf { currentRole == role }
    }
}

/**
 * Check if user has any of the specified roles from composition.
 *
 * Usage:
 * ```kotlin
 * @Composable
 * fun AdminPanel() {
 *     val isAdmin by hasAnyRole(EmployeeRole.ADMIN, EmployeeRole.OWNER)
 *     if (isAdmin) {
 *         AdminControls()
 *     }
 * }
 * ```
 *
 * @param roles The roles to check (needs at least one)
 * @return State containing true if user has any of the roles
 */
@Composable
fun hasAnyRole(vararg roles: EmployeeRole): State<Boolean> {
    val currentRole by rememberRole()
    return remember(currentRole, roles.toList()) {
        derivedStateOf { currentRole != null && roles.contains(currentRole) }
    }
}

/**
 * Check if user is manager or higher from composition.
 *
 * Convenience function for manager-level permissions.
 *
 * Usage:
 * ```kotlin
 * @Composable
 * fun ReportsSection() {
 *     val isManagerOrHigher by isManagerOrHigher()
 *     if (isManagerOrHigher) {
 *         DetailedReports()
 *     } else {
 *         BasicSummary()
 *     }
 * }
 * ```
 *
 * @return State containing true if user is MANAGER, ADMIN, or OWNER
 */
@Composable
fun isManagerOrHigher(): State<Boolean> =
    hasAnyRole(
        EmployeeRole.MANAGER,
        EmployeeRole.ADMIN,
    )

/**
 * Check if user is admin or owner from composition.
 *
 * Convenience function for admin-level permissions.
 *
 * Usage:
 * ```kotlin
 * @Composable
 * fun SystemSettings() {
 *     val isAdmin by isAdminOrOwner()
 *     if (isAdmin) {
 *         AdvancedSettings()
 *     }
 * }
 * ```
 *
 * @return State containing true if user is ADMIN or OWNER
 */
@Composable
fun isAdmin(): State<Boolean> = hasAnyRole(EmployeeRole.ADMIN)

/**
 * Composable wrapper that renders content if user has role.
 *
 * Usage:
 * ```kotlin
 * WithRole(EmployeeRole.MANAGER) {
 *     ManagerDashboard()
 * }
 * ```
 *
 * @param role The role required to render content
 * @param content The composable content to render if role matches
 */
@Composable
fun WithRole(
    role: EmployeeRole,
    content: @Composable () -> Unit,
) {
    val hasIt by hasRole(role)
    if (hasIt) {
        content()
    }
}

/**
 * Composable wrapper that renders different content based on role.
 *
 * Usage:
 * ```kotlin
 * RoleGate(
 *     role = EmployeeRole.MANAGER,
 *     matched = { ManagerView() },
 *     unmatched = { BasicView() }
 * )
 * ```
 *
 * @param role The role to check
 * @param matched Content to render if role matches
 * @param unmatched Content to render if role doesn't match
 */
@Composable
fun RoleGate(
    role: EmployeeRole,
    matched: @Composable () -> Unit,
    unmatched: @Composable () -> Unit = {},
) {
    val hasIt by hasRole(role)
    if (hasIt) {
        matched()
    } else {
        unmatched()
    }
}

/**
 * Track a privileged operation for temporary session auto-extension.
 *
 * Call this function BEFORE performing manager-level operations (order cancellation,
 * bill deletion, price overrides, etc.) to automatically extend the temporary session
 * if it's about to expire (≤ 30 seconds remaining).
 *
 * Usage:
 * ```kotlin
 * @Composable
 * fun CancelOrderButton(orderId: String) {
 *     val sessionManager = LocalTemporarySessionManager.current
 *
 *     Button(onClick = {
 *         trackPrivilegedOperation(sessionManager)
 *         cancelOrder(orderId)
 *     }) {
 *         Text("Cancel Order")
 *     }
 * }
 * ```
 *
 * @param sessionManager The TemporarySessionManager from composition (nullable)
 */
fun trackPrivilegedOperation(sessionManager: TemporarySessionManager?) {
    sessionManager?.trackPrivilegedOperation()
}
