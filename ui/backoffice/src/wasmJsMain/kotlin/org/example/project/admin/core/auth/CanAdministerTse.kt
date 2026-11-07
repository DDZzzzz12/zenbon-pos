package org.example.project.admin.core.auth

import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.enums.auth.Capability

/**
 * The TSE surface — the fiscal control plane, and the strictest gate in the console.
 *
 * `TSE_ADMIN` is `Assignability.RESERVED`, so only the ADMIN group holds it and no group edit can
 * hand it out. That is stronger than the `role == ADMIN` check it replaces, which any future role
 * change could have widened silently.
 */
val TokenStore.canAdministerTse: Boolean
    get() = holds(Capability.TSE_ADMIN)
