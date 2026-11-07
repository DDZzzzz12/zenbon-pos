package org.example.project.admin.core.auth

import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.enums.auth.Capability

/**
 * Whether this principal may make accounting decisions — today, change or confirm the Buchungsbeginn.
 *
 * The server enforces `ACCOUNTING_WRITE` on `PUT /api/finance/bookkeeping-start` and on its
 * `/acknowledge` (`BookkeepingStartRules`); this only hides the controls (CLAUDE.md rule 8), so a
 * read-only role such as AUDITOR is not offered a button that answers 403.
 */
val TokenStore.canWriteAccounting: Boolean
    get() = holds(Capability.ACCOUNTING_WRITE)
