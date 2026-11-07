/**
 * API client interface for voucher operations.
 *
 * Stage C Batch 7: throws-style.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.remote

import org.example.project.core.domain.dto.voucher.VoucherDto

interface VoucherApiClient {
    suspend fun createVoucher(request: VoucherDto.CreateVoucherRequestDto): VoucherDto.VoucherResponseDto

    suspend fun validateVoucher(request: VoucherDto.ValidateVoucherRequestDto): VoucherDto.ValidateVoucherResponseDto

    suspend fun getVouchersCreatedInRange(
        startDate: String,
        endDate: String,
        employeeId: String? = null,
    ): List<VoucherDto.VoucherResponseDto>

    suspend fun getVouchersRedeemedInRange(
        startDate: String,
        endDate: String,
        employeeId: String? = null,
    ): List<VoucherDto.VoucherRedemptionSummaryDto>

    suspend fun getVoucherStatistics(): VoucherDto.VoucherStatisticsDto

    /** All vouchers with a remaining balance, newest first. Not date-scoped — this is outstanding liability. */
    suspend fun getActiveVouchers(
        limit: Int? = null,
        offset: Int = 0,
    ): List<VoucherDto.VoucherResponseDto>

    /** Server-side search by voucher-number pattern across every period. [searchTerm] must not be blank. */
    suspend fun searchVouchersByNumber(
        searchTerm: String,
        limit: Int? = null,
        offset: Int = 0,
    ): List<VoucherDto.VoucherResponseDto>

    suspend fun getRedemptionHistory(voucherNumber: String): VoucherDto.VoucherRedemptionHistoryDto
}
