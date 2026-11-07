package org.example.project.admin.core.auth

import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.enums.auth.Capability

/**
 * Revoking other people's sessions and editing the session policy.
 *
 * `SESSION_REVOKE` is what the server requires on `PUT /api/auth/sessions/policy` and
 * `POST /api/auth/sessions/revoke-all`, and it is NOT in the MANAGER bundle — so this stays
 * admin-only, exactly as the `role == ADMIN` check it replaces. (`SESSION_READ`/`SESSION_WRITE` ARE
 * manager-level and would have quietly widened this; the capability a route names is the only
 * reliable source for a gate.)
 */
val TokenStore.canAdministerSessions: Boolean
    get() = holds(Capability.SESSION_REVOKE)
