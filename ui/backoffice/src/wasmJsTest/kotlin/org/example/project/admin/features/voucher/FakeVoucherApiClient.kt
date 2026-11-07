package org.example.project.admin.features.voucher

import org.example.project.core.data.remote.VoucherApiClient
import org.example.project.core.domain.dto.voucher.VoucherDto

class FakeVoucherApiClient : VoucherApiClient {
    var issued: List<VoucherDto.VoucherResponseDto> = emptyList()
    var redeemed: List<VoucherDto.VoucherRedemptionSummaryDto> = emptyList()
    var active: List<VoucherDto.VoucherResponseDto> = emptyList()
    var searchResults: List<VoucherDto.VoucherResponseDto> = emptyList()
    var statistics: VoucherDto.VoucherStatisticsDto? = null
    var history: VoucherDto.VoucherRedemptionHistoryDto? = null

    var issuedCalls = 0
    var redeemedCalls = 0
    var activeCalls = 0
    var searchCalls = 0
    var statisticsCalls = 0
    var lastEmployeeId: String? = null
    var lastOffset: Int = -1

    var issuedFailure: (() -> Nothing)? = null
    var statisticsFailure: (() -> Nothing)? = null

    /**
     * When > 0, [getVouchersCreatedInRange] and [getVouchersRedeemedInRange] suspend this long
     * before returning — same idea as [searchDelayMs], but for the period-load pair, so a test can
     * make one `load()` call outlive a second one and prove the stale response cannot win the race.
     */
    var rangeDelayMs: Long = 0

    override suspend fun createVoucher(request: VoucherDto.CreateVoucherRequestDto): Nothing = throw NotImplementedError()

    override suspend fun validateVoucher(request: VoucherDto.ValidateVoucherRequestDto): Nothing = throw NotImplementedError()

    override suspend fun getVouchersCreatedInRange(
        startDate: String,
        endDate: String,
        employeeId: String?,
    ): List<VoucherDto.VoucherResponseDto> {
        issuedCalls++
        lastEmployeeId = employeeId
        issuedFailure?.invoke()
        if (rangeDelayMs > 0) kotlinx.coroutines.delay(rangeDelayMs)
        return issued
    }

    override suspend fun getVouchersRedeemedInRange(
        startDate: String,
        endDate: String,
        employeeId: String?,
    ): List<VoucherDto.VoucherRedemptionSummaryDto> {
        redeemedCalls++
        if (rangeDelayMs > 0) kotlinx.coroutines.delay(rangeDelayMs)
        return redeemed
    }

    override suspend fun getVoucherStatistics(): VoucherDto.VoucherStatisticsDto {
        statisticsCalls++
        statisticsFailure?.invoke()
        return statistics ?: error("no statistics configured")
    }

    override suspend fun getActiveVouchers(
        limit: Int?,
        offset: Int,
    ): List<VoucherDto.VoucherResponseDto> {
        activeCalls++
        lastOffset = offset
        return active
    }

    /** When > 0, search suspends this long so a test can cancel it mid-flight. */
    var searchDelayMs: Long = 0

    override suspend fun searchVouchersByNumber(
        searchTerm: String,
        limit: Int?,
        offset: Int,
    ): List<VoucherDto.VoucherResponseDto> {
        searchCalls++
        if (searchDelayMs > 0) kotlinx.coroutines.delay(searchDelayMs)
        return searchResults
    }

    override suspend fun getRedemptionHistory(voucherNumber: String): VoucherDto.VoucherRedemptionHistoryDto = history ?: error("no history configured")
}
