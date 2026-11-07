package org.example.project.admin.core.auth

import org.example.project.core.auth.AccessPolicy
import org.example.project.core.domain.enums.auth.Capability

/**
 * Whether the principal may list the permission groups: `PERMISSION_ADMIN`, what
 * `GET /api/admin/permission-groups` requires. The staff screen asks before loading the groups for
 * its picker, so a caller without it never fires a request the server refuses with 403 (NEW-22).
 */
fun canListPermissionGroups(policy: AccessPolicy): Boolean = policy.allows(Capability.PERMISSION_ADMIN)
