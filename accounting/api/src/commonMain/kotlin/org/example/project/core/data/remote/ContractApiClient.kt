package org.example.project.core.data.remote

import org.example.project.core.domain.dto.contract.ContractAlertDto
import org.example.project.core.domain.dto.contract.ContractDto

/**
 * Verträge — recurring obligations and the instalments they fall due in. Every endpoint is
 * MANAGER-only server-side.
 *
 * **There is no delete.** A contract deactivates through [update]'s `isActive`: an instalment
 * already allocated against is history.
 */
interface ContractApiClient {
    /** Name-ordered. [includeInactive] adds the ones an operator has switched off. */
    /** [search] matches name or description server-side; a blank term is not a filter. */
    suspend fun list(
        includeInactive: Boolean = false,
        search: String? = null,
    ): ContractDto.ListResponseDto

    suspend fun get(contractId: Long): ContractDto.ResponseDto

    suspend fun create(request: ContractDto.CreateRequestDto): ContractDto.ResponseDto

    /** A full replace of the editable fields, not a patch — an omitted optional field is written null. */
    suspend fun update(
        contractId: Long,
        request: ContractDto.UpdateRequestDto,
    ): ContractDto.ResponseDto

    /** Due date ascending. Each status is derived server-side; the stored column holds only OPEN or WAIVED. */
    suspend fun instalments(contractId: Long): ContractDto.InstalmentListDto

    /**
     * What every **active** contract is currently complaining about, across all of them.
     *
     * Derived server-side on every read, so there is nothing to acknowledge: an alert disappears by
     * itself once the payment lands, the invoice arrives or the amount is corrected.
     */
    suspend fun alerts(): ContractAlertDto.ListResponseDto
}
