package org.example.project.admin.features.debtors.presentation

import org.example.project.core.auth.AccessPolicy
import org.example.project.core.domain.enums.auth.Capability

/**
 * Whether the signed-in principal may create, edit or delete a Debitor.
 *
 * `POST /api/debtors`, `PUT /api/debtors/{id}` and `DELETE /api/debtors/{id}` are all `DEBTOR_WRITE`
 * in `DebtorRules`; a read-only group (Prüfer) sees the debtors without those controls (FO-03).
 */
data class DebtorsAccess(
    val mayWrite: Boolean,
) {
    companion object {
        fun of(policy: AccessPolicy): DebtorsAccess = DebtorsAccess(mayWrite = policy.allows(Capability.DEBTOR_WRITE))
    }
}
