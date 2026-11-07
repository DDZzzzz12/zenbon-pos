package org.example.project.admin.features.dashboard

import org.example.project.core.data.remote.ZBonApiClient
import org.example.project.core.domain.dto.report.ZBonDto

class FakeZBonApiClient(
    private val latestEndTime: String? = "2026-07-10T08:00:00",
    private val recentReports: () -> List<ZBonDto.ZBonSummeryDto> = { emptyList() },
    /** Answers [getById]; unset, it throws like every other unused method. */
    private val byId: ((Long) -> ZBonDto.ZBonResponseDto)? = null,
) : ZBonApiClient {
    override suspend fun getLatestZBonEndTime(): String? = latestEndTime

    override suspend fun getRecentReports(limit: Int): List<ZBonDto.ZBonSummeryDto> = recentReports()

    override suspend fun createZBon(
        countedAmount: String?,
        tipPayouts: List<ZBonDto.CreateTipPayoutRequestDto>,
        topUp: ZBonDto.ClosingTopUpRequestDto?,
    ): ZBonDto.ZBonResponseDto {
        createZBonRequests += ZBonDto.CreateZBonRequestDto(countedAmount, tipPayouts, topUp)
        createZBonThrows?.let { throw it }
        return createZBonResult
    }

    /** Every closing sent, as the request body it amounts to. Empty proves nothing reached the wire. */
    val createZBonRequests: MutableList<ZBonDto.CreateZBonRequestDto> = mutableListOf()
    var createZBonThrows: Throwable? = null
    var createZBonResult: ZBonDto.ZBonResponseDto =
        ZBonDto.ZBonResponseDto(
            id = 7,
            zbonNummer = 12,
            shopInfoId = 1,
            shopName = "Shop",
            employeeNumber = "1",
            startTime = "2026-10-09T08:00:00",
            endTime = "2026-10-09T22:00:00",
            cancelledAmount = "0.00",
            totalRevenue = "100.00",
            openingBalance = "0.00",
            expenses = "0.00",
            closingBalance = "0.00",
            createdAt = "2026-10-09T22:00:00",
        )

    /** The open period's tips; unset, the lookup throws like every other unused method. */
    var openPeriodTipsResult: List<ZBonDto.OpenPeriodTipDto>? = null

    override suspend fun getOpenPeriodTips(): List<ZBonDto.OpenPeriodTipDto> = openPeriodTipsResult ?: throw NotImplementedError()

    override suspend fun getZBonHistory(
        startDate: String,
        endDate: String,
    ): List<ZBonDto.ZBonSummeryDto> = throw NotImplementedError()

    override suspend fun getById(zbonId: Long): ZBonDto.ZBonResponseDto = byId?.invoke(zbonId) ?: throw NotImplementedError()

    override suspend fun printZBon(zbonId: Long) = throw NotImplementedError()

    override suspend fun downloadZBonPdf(zbonId: Long): ByteArray = throw NotImplementedError()

    override suspend fun payOutTip(
        zbonId: Long,
        request: ZBonDto.CreateTipPayoutRequestDto,
    ): ZBonDto.TipPayoutResponseDto = throw NotImplementedError()

    override suspend fun reverseTipPayout(
        zbonId: Long,
        payoutHeaderId: Long,
        request: ZBonDto.ReverseTipPayoutRequestDto,
    ): ZBonDto.TipPayoutResponseDto = throw NotImplementedError()

    override suspend fun reverseTipSettlement(
        zbonId: Long,
        payoutId: Long,
        request: ZBonDto.ReverseTipPayoutRequestDto,
    ): ZBonDto.TipPayoutResponseDto = throw NotImplementedError()

    var closingReadinessResult: ZBonDto.ClosingReadinessDto = ZBonDto.ClosingReadinessDto(signerName = "Kasse 1")
    var closingReadinessThrows: Throwable? = null

    override suspend fun getClosingReadiness(): ZBonDto.ClosingReadinessDto {
        closingReadinessThrows?.let { throw it }
        return closingReadinessResult
    }
}
