package org.example.project.admin.features.supplier.presentation

import org.example.project.core.auth.AccessPolicy
import org.example.project.core.domain.enums.auth.Capability

/**
 * Whether the signed-in principal may create or edit a supplier.
 *
 * `POST /api/suppliers` and `PUT /api/suppliers/{id}` are `CREDITOR_WRITE` in `CreditorRules`
 * (FO-03); reading is `CREDITOR_READ`.
 */
data class SupplierAccess(
    val mayWrite: Boolean,
) {
    companion object {
        fun of(policy: AccessPolicy): SupplierAccess = SupplierAccess(mayWrite = policy.allows(Capability.CREDITOR_WRITE))
    }
}
