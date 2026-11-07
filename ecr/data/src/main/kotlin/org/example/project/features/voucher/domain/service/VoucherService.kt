/**
 * Service interface for Voucher business operations.
 *
 * Defines business logic operations for Voucher management including
 * validation, transformation, redemption processing, and coordination
 * with repository layer. Handles gift voucher and store credit functionality.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors;
 * StatusPages catches it and emits application/problem+json.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.voucher.domain.service

import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.domain.enums.auth.Capability

/**
 * Service interface for Voucher business operations.
 */
interface VoucherService {
    suspend fun getAllVouchers(
        limit: Int? = null,
        offset: Int = 0,
        includeRedeemed: Boolean = true,
    ): List<VoucherDto.VoucherResponseDto>

    suspend fun getVoucherById(id: Long): VoucherDto.VoucherResponseDto

    suspend fun getVoucherByNumber(voucherNumber: String): VoucherDto.VoucherResponseDto

    suspend fun getActiveVouchers(
        limit: Int? = null,
        offset: Int = 0,
    ): List<VoucherDto.VoucherResponseDto>

    suspend fun searchVouchersByNumber(
        searchTerm: String,
        limit: Int? = null,
        offset: Int = 0,
    ): List<VoucherDto.VoucherResponseDto>

    /**
     * Issues a voucher.
     *
     * @param actorCapabilities What the issuing principal holds for this request, as the route gate
     *   resolved it (`call.heldCapabilities()`, including an active step-up). A table-session voucher
     *   is booked through `OrderService.createOrder` as a custom-price line, and that check reads this
     *   set rather than re-resolving the employee's group (R5-1). That line is refused under the
     *   order station's shared account like any other priced line (`PriceAuthority.mayPriceLinesAs`).
     */
    suspend fun createVoucher(
        employeeId: String,
        deviceId: String,
        request: VoucherDto.CreateVoucherRequestDto,
        actorCapabilities: Set<Capability>,
    ): VoucherDto.VoucherResponseDto

    suspend fun voucherNumberExists(
        voucherNumber: String,
        excludeId: Long? = null,
    ): Boolean

    suspend fun getVoucherStatistics(employeeId: String?): VoucherDto.VoucherStatisticsDto

    suspend fun getVoucherRedemptionHistory(voucherNumber: String): VoucherDto.VoucherRedemptionHistoryDto

    suspend fun validateVoucher(request: VoucherDto.ValidateVoucherRequestDto): VoucherDto.ValidateVoucherResponseDto

    suspend fun getVouchersCreatedInRange(
        startDate: String,
        endDate: String,
        employeeId: String? = null,
        filterEmployeeId: String? = null,
        seesEveryone: Boolean = false,
    ): List<VoucherDto.VoucherResponseDto>

    suspend fun getVouchersRedeemedInRange(
        startDate: String,
        endDate: String,
        employeeId: String? = null,
        filterEmployeeId: String? = null,
        seesEveryone: Boolean = false,
    ): List<VoucherDto.VoucherRedemptionSummaryDto>
}
