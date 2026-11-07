package org.example.project.admin.features.contracts.presentation

import org.example.project.core.auth.AccessPolicy
import org.example.project.core.domain.enums.auth.Capability

/**
 * Whether the signed-in principal may create or edit a contract.
 *
 * `POST /api/contracts` and `PUT /api/contracts/{id}` are `CONTRACT_WRITE` in `ContractRules`
 * (FO-03); reading is `CONTRACT_READ`.
 */
data class ContractsAccess(
    val mayWrite: Boolean,
) {
    companion object {
        fun of(policy: AccessPolicy): ContractsAccess = ContractsAccess(mayWrite = policy.allows(Capability.CONTRACT_WRITE))
    }
}
