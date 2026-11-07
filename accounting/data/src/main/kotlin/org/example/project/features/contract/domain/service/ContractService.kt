package org.example.project.features.contract.domain.service

import org.example.project.core.domain.dto.contract.ContractAlertDto
import org.example.project.core.domain.dto.contract.ContractDto

/**
 * Recurring obligations, as the HTTP layer sees it.
 *
 * Returns DTOs and throws `PosException` on error, per the server's service-layer contract. The
 * entity-to-DTO mapping happens inside the service's own `dbQuery`, because the supplier and invoice
 * references it renders are lazily-loaded Exposed relations that are unavailable once the
 * transaction closes.
 *
 * Every method is **shop-implicit** — the system is single-shop and adminApp has no shopId to send,
 * so the shop is resolved server-side.
 *
 * There is no delete: a contract deactivates through [update]'s `isActive`.
 */
interface ContractService {
    /** Name-ordered. [includeInactive] adds the contracts an operator has switched off. */
    /** [search] matches name or description, applied server-side so the switch and box agree. */
    suspend fun list(
        includeInactive: Boolean,
        search: String?,
    ): ContractDto.ListResponseDto

    suspend fun get(id: Long): ContractDto.ResponseDto

    suspend fun create(request: ContractDto.CreateRequestDto): ContractDto.ResponseDto

    suspend fun update(
        id: Long,
        request: ContractDto.UpdateRequestDto,
    ): ContractDto.ResponseDto

    /**
     * The contract's expected payments, due date ascending, each with a **derived** status — the
     * stored column holds only `OPEN` or `WAIVED`.
     */
    suspend fun instalments(contractId: Long): ContractDto.InstalmentListDto

    /**
     * Everything every **active** contract is currently complaining about — a payment that never
     * went out, an invoice that never arrived, an amount that changed behind our back.
     *
     * Derived on read, never stored, so an alert disappears by itself when the situation is
     * resolved. A deactivated contract raises nothing: switching it off is the operator saying they
     * no longer want to hear about it.
     */
    suspend fun alerts(): ContractAlertDto.ListResponseDto
}
