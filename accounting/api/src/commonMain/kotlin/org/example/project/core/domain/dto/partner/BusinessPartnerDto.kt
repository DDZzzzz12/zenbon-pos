package org.example.project.core.domain.dto.partner

import kotlinx.serialization.Serializable

/** Wire contract for business partners — the company both accounting roles point at. */
object BusinessPartnerDto {
    @Serializable
    data class ResponseDto(
        val id: Long,
        val name: String,
        val legalName: String? = null,
        val vatId: String? = null,
        val taxNumber: String? = null,
        val street: String? = null,
        val zip: String? = null,
        val city: String? = null,
        val country: String? = null,
        /** True when this party carries a creditor role, so a reviewer sees what a merge would move. */
        val hasCreditorRole: Boolean = false,
        /** True when this party carries a debtor role. */
        val hasDebtorRole: Boolean = false,
        val knownIbans: List<String> = emptyList(),
    )

    /** One group of parties that are probably the same company, and what they agree on. */
    @Serializable
    data class DuplicateGroupDto(
        val key: String,
        val partners: List<ResponseDto>,
    )

    @Serializable
    data class DuplicateListResponseDto(
        val groups: List<DuplicateGroupDto>,
    )

    /**
     * Both ids are named explicitly rather than inferred.
     *
     * Which party survives decides which creditor number and which stored references stay valid, so
     * it is the operator's call and must be legible in the request rather than derived from
     * whichever id happened to be lower.
     */
    @Serializable
    data class MergeRequestDto(
        val survivorId: Long,
        val losingId: Long,
    )
}
