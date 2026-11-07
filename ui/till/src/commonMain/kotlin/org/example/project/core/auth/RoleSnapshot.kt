package org.example.project.core.auth

import org.example.project.core.domain.enums.employee.EmployeeRole

/**
 * What [PermissionManager.readRoleSnapshot] reads from storage for a signed-in principal, published by
 * [PermissionManager.applyRoleSnapshot].
 *
 * Two steps because reading opens the sealed access token, which can block on the Keystore or keychain and so runs on
 * the IO dispatcher, while the StateFlow writes run on the caller's context once the read has returned. A caller
 * cancelled in between — `SettingProvider`'s effect, when the session ends — then never applies a role read before the
 * logout.
 *
 * @property capabilities the capability names as stored, or null when none were reported.
 */
data class RoleSnapshot(
    val role: EmployeeRole,
    val employeeNumber: String?,
    val employeeName: String?,
    val trainingMode: Boolean,
    val capabilities: Set<String>?,
    /** The token's employee id; published as the signed-in person last, after the role. Null skips it (tests). */
    val employeeId: String? = null,
)
