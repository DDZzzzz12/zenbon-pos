package org.example.project.features.partner.domain.service

import org.example.project.core.domain.dto.partner.BusinessPartnerDto

/** Business partners — the company behind the creditor and debtor roles. */
interface BusinessPartnerService {
    /** Parties that are probably the same company. Already-merged parties are excluded. */
    suspend fun duplicates(limit: Int): BusinessPartnerDto.DuplicateListResponseDto

    /** One party, following a merge pointer to the survivor. Null when the id does not exist. */
    suspend fun get(id: Long): BusinessPartnerDto.ResponseDto?

    /**
     * Declares [losingId] and [survivorId] the same company.
     *
     * Roles and bank accounts move to the survivor; the losing row is kept with a pointer to it, so
     * the decision is reversible and stored references keep resolving.
     *
     * @param actingEmployeeId the operator to name in the audit entry, or null when no principal is
     *   available. Null is accepted rather than defaulted so a caller cannot silently attribute a
     *   merge to nobody by omission.
     */
    suspend fun merge(
        survivorId: Long,
        losingId: Long,
        actingEmployeeId: String? = null,
    )
}
