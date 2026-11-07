package org.example.project.admin.core.auth

import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.enums.auth.Capability

/**
 * Whether this principal may edit the catalog.
 *
 * **Was a role check** (`isManagerOrHigher()`), and was used across the sidebar as a general
 * "is a manager" gate — which is exactly the coarse-grained thing capabilities replace. It now means
 * what its name says. The sidebar entries that were leaning on it for *other* screens each carry
 * their own capability in `AdminNavItem`, so an accountant keeps invoices and debtors while losing
 * the catalog, instead of getting all or nothing.
 *
 * The server enforces `CATALOG_MANAGE` on the same routes; this only hides controls (CLAUDE.md
 * rule 8).
 */
val TokenStore.canManageProducts: Boolean
    get() = holds(Capability.CATALOG_MANAGE)
