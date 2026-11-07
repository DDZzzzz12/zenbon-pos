package org.example.project.testutils.fakes

import org.example.project.core.data.remote.ZBonApiClient
import org.example.project.core.domain.dto.report.ZBonDto

class FakeZBonApiClient : ZBonApiClient {
    var getLatestZBonEndTimeResult: String? = null
    var createZBonResult: ZBonDto.ZBonResponseDto? = null
    var getRecentReportsResult: List<ZBonDto.ZBonSummeryDto> = emptyList()
    var getZBonHistoryResult: List<ZBonDto.ZBonSummeryDto> = emptyList()
    var getByIdResult: ZBonDto.ZBonResponseDto? = null
    var downloadZBonPdfResult: ByteArray = ByteArray(0)

    var getLatestZBonEndTimeThrows: Throwable? = null
    var createZBonThrows: Throwable? = null
    var getRecentReportsThrows: Throwable? = null
    var getZBonHistoryThrows: Throwable? = null
    var getByIdThrows: Throwable? = null
    var printZBonThrows: Throwable? = null
    var downloadZBonPdfThrows: Throwable? = null

    var createZBonCallCount = 0
    var getRecentReportsCallCount = 0
    var getZBonHistoryCallCount = 0
    var getByIdCallCount = 0
    var printZBonCallCount = 0
    var downloadZBonPdfCallCount = 0

    var lastGetZBonHistoryStartDate: String? = null
    var lastGetZBonHistoryEndDate: String? = null
    var lastGetByIdZbonId: Long? = null
    var lastPrintZBonId: Long? = null
    var lastDownloadZBonPdfId: Long? = null

    override suspend fun getLatestZBonEndTime(): String? {
        getLatestZBonEndTimeThrows?.let { throw it }
        return getLatestZBonEndTimeResult
    }

    /** The countedAmount of every createZBon call, in order. */
    val createdWithCounts = mutableListOf<String?>()

    /** The tipPayouts of every createZBon call, in order (F58). */
    val createdWithTipPayouts = mutableListOf<List<ZBonDto.CreateTipPayoutRequestDto>>()

    /** The topUp of every createZBon call, in order (F59). */
    val createdWithTopUps = mutableListOf<ZBonDto.ClosingTopUpRequestDto?>()

    override suspend fun createZBon(
        countedAmount: String?,
        tipPayouts: List<ZBonDto.CreateTipPayoutRequestDto>,
        topUp: ZBonDto.ClosingTopUpRequestDto?,
    ): ZBonDto.ZBonResponseDto {
        createZBonCallCount++
        createdWithCounts += countedAmount
        createdWithTipPayouts += tipPayouts
        createdWithTopUps += topUp
        createZBonThrows?.let { throw it }
        return createZBonResult ?: error("createZBonResult not set")
    }

    var openPeriodTipsResult: List<ZBonDto.OpenPeriodTipDto> = emptyList()
    var openPeriodTipsThrows: Throwable? = null
    var openPeriodTipsCallCount = 0

    override suspend fun getOpenPeriodTips(): List<ZBonDto.OpenPeriodTipDto> {
        openPeriodTipsCallCount++
        openPeriodTipsThrows?.let { throw it }
        return openPeriodTipsResult
    }

    override suspend fun getRecentReports(limit: Int): List<ZBonDto.ZBonSummeryDto> {
        getRecentReportsCallCount++
        getRecentReportsThrows?.let { throw it }
        return getRecentReportsResult
    }

    override suspend fun getZBonHistory(
        startDate: String,
        endDate: String,
    ): List<ZBonDto.ZBonSummeryDto> {
        getZBonHistoryCallCount++
        lastGetZBonHistoryStartDate = startDate
        lastGetZBonHistoryEndDate = endDate
        getZBonHistoryThrows?.let { throw it }
        return getZBonHistoryResult
    }

    override suspend fun getById(zbonId: Long): ZBonDto.ZBonResponseDto {
        getByIdCallCount++
        lastGetByIdZbonId = zbonId
        getByIdThrows?.let { throw it }
        return getByIdResult ?: error("getByIdResult not set")
    }

    override suspend fun printZBon(zbonId: Long) {
        printZBonCallCount++
        lastPrintZBonId = zbonId
        printZBonThrows?.let { throw it }
    }

    override suspend fun downloadZBonPdf(zbonId: Long): ByteArray {
        downloadZBonPdfCallCount++
        lastDownloadZBonPdfId = zbonId
        downloadZBonPdfThrows?.let { throw it }
        return downloadZBonPdfResult
    }

    var payOutTipResult: ZBonDto.TipPayoutResponseDto? = null
    var payOutTipThrows: Throwable? = null

    /** Every payOutTip call as (zbonId, request), in order. */
    val payOutTipCalls = mutableListOf<Pair<Long, ZBonDto.CreateTipPayoutRequestDto>>()

    override suspend fun payOutTip(
        zbonId: Long,
        request: ZBonDto.CreateTipPayoutRequestDto,
    ): ZBonDto.TipPayoutResponseDto {
        payOutTipCalls += zbonId to request
        payOutTipThrows?.let { throw it }
        return payOutTipResult ?: error("payOutTipResult not set")
    }

    var reverseTipPayoutResult: ZBonDto.TipPayoutResponseDto? = null
    var reverseTipPayoutThrows: Throwable? = null

    /** Every reverseTipPayout call as (zbonId, payoutHeaderId, request), in order. */
    val reverseTipPayoutCalls = mutableListOf<Triple<Long, Long, ZBonDto.ReverseTipPayoutRequestDto>>()

    override suspend fun reverseTipPayout(
        zbonId: Long,
        payoutHeaderId: Long,
        request: ZBonDto.ReverseTipPayoutRequestDto,
    ): ZBonDto.TipPayoutResponseDto {
        reverseTipPayoutCalls += Triple(zbonId, payoutHeaderId, request)
        reverseTipPayoutThrows?.let { throw it }
        return reverseTipPayoutResult ?: error("reverseTipPayoutResult not set")
    }

    var reverseTipSettlementResult: ZBonDto.TipPayoutResponseDto? = null

    /** Every reverseTipSettlement call as (zbonId, payoutId, request), in order (F59). */
    val reverseTipSettlementCalls = mutableListOf<Triple<Long, Long, ZBonDto.ReverseTipPayoutRequestDto>>()

    override suspend fun reverseTipSettlement(
        zbonId: Long,
        payoutId: Long,
        request: ZBonDto.ReverseTipPayoutRequestDto,
    ): ZBonDto.TipPayoutResponseDto {
        reverseTipSettlementCalls += Triple(zbonId, payoutId, request)
        return reverseTipSettlementResult ?: error("reverseTipSettlementResult not set")
    }

    var closingReadinessResult: ZBonDto.ClosingReadinessDto = ZBonDto.ClosingReadinessDto(signerName = "Kasse 1")
    var closingReadinessThrows: Throwable? = null

    override suspend fun getClosingReadiness(): ZBonDto.ClosingReadinessDto {
        closingReadinessThrows?.let { throw it }
        return closingReadinessResult
    }
}
