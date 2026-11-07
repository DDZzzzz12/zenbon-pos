package org.example.project.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.example.project.core.auth.AccessPolicy
import org.example.project.core.auth.LocalPermissionManager
import org.example.project.core.auth.LocalSettingManager
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.ui.auth.LocalAccessPolicy
import org.example.project.ui.auth.rememberIsStation

@Composable
fun rememberVisibleDestinations(group: NavGroup): List<PosNavDestination> {
    val permissionManager = LocalPermissionManager.current
    val role by permissionManager.effectiveRole.collectAsState()
    val policy = LocalAccessPolicy.current
    val settingManager = LocalSettingManager.current
    val voucherEnabled by settingManager.voucherEnabled.collectAsState()
    val isStation = rememberIsStation()

    return remember(role, policy, voucherEnabled, group, isStation) {
        PosNavDestination.entries
            .filter { it.group == group }
            .filter { it.isAccessibleTo(policy, role, voucherEnabled, isStation) }
    }
}

/**
 * Whether this destination is accessible to the principal.
 *
 * Two axes, asked in order. The STATION axis: an unattended station ([isStation]) only sees
 * destinations flagged [PosNavDestination.visibleToTerminal]; a null [role] — no active session —
 * is treated as station-level, as before. The IDENTITY axis: [PosNavDestination.requiredCapability]
 * against [policy], the same question the server asks. Voucher-only destinations additionally need
 * [voucherEnabled].
 *
 * This is the single source of truth shared by the navigation menu and by the "leave a screen the
 * principal can no longer access" guard in MainScreen — when a temporary manager session expires, a
 * destination that becomes inaccessible here triggers a redirect back to the dashboard.
 *
 * [role] is no longer compared to anything; it survives as the "is there a session at all" signal.
 */
fun PosNavDestination.isAccessibleTo(
    policy: AccessPolicy,
    role: EmployeeRole?,
    voucherEnabled: Boolean,
    isStation: Boolean,
): Boolean {
    if (role == null) return visibleToTerminal
    if (isStation && !visibleToTerminal) return false

    val required = requiredCapability
    if (required != null && !policy.allows(required)) return false

    if (requiresVoucher && !voucherEnabled) return false
    return true
}
