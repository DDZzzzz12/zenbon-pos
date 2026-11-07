package org.example.project.admin.core.auth

import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.enums.auth.Capability

/**
 * Whether this principal may build custom exports, as opposed to running ones somebody else built.
 *
 * The server enforces `CUSTOM_EXPORT_MANAGE` on the create and delete routes; this only hides
 * controls (CLAUDE.md rule 8). Running is deliberately *not* gated on it — a reader may run a saved
 * definition, and the run re-checks every field against their own capabilities server-side.
 */
val TokenStore.canManageCustomExports: Boolean
    get() = holds(Capability.CUSTOM_EXPORT_MANAGE)
