package org.example.project.core.data.remote

import org.example.project.core.domain.dto.report.ZBonDto

/**
 * Stage C Batch 8: throws-style.
 */
interface ZBonApiClient {
    suspend fun getLatestZBonEndTime(): String?

    /**
     * Closes the day. [countedAmount] (the drawer count, dot decimal) travels with the request; the
     * server books its Kassendifferenz only once the closing's preconditions pass. [tipPayouts] (F58)
     * are employee-tip payouts the server books inside the closing's own transaction. [topUp] (F59)
     * puts cash from another book into the drawer inside the closing, before the cash payouts.
     */
    suspend fun createZBon(
        countedAmount: String? = null,
        tipPayouts: List<ZBonDto.CreateTipPayoutRequestDto> = emptyList(),
        topUp: ZBonDto.ClosingTopUpRequestDto? = null,
    ): ZBonDto.ZBonResponseDto

    /** The open period's employee tips per employee, for the closing dialog: `GET /api/z-bon/open-period-tips`. */
    suspend fun getOpenPeriodTips(): List<ZBonDto.OpenPeriodTipDto>

    suspend fun getRecentReports(limit: Int): List<ZBonDto.ZBonSummeryDto>

    suspend fun getZBonHistory(
        startDate: String,
        endDate: String,
    ): List<ZBonDto.ZBonSummeryDto>

    suspend fun getById(zbonId: Long): ZBonDto.ZBonResponseDto

    suspend fun printZBon(zbonId: Long)

    suspend fun downloadZBonPdf(zbonId: Long): ByteArray

    /**
     * Pays part or all of an employee's tips from closing [zbonId] out of the drawer:
     * `POST /api/z-bon/{id}/tip-payouts`. Answers the booked Beleg and the employee's new position.
     */
    suspend fun payOutTip(
        zbonId: Long,
        request: ZBonDto.CreateTipPayoutRequestDto,
    ): ZBonDto.TipPayoutResponseDto

    /** Stornos the tip payout booked as Kassenbuch entry [payoutHeaderId]: `POST /api/z-bon/{id}/tip-payouts/{payoutHeaderId}/reverse`. */
    suspend fun reverseTipPayout(
        zbonId: Long,
        payoutHeaderId: Long,
        request: ZBonDto.ReverseTipPayoutRequestDto,
    ): ZBonDto.TipPayoutResponseDto

    /**
     * Stornos the bank/payroll tip settlement [payoutId] (F59) — no Kassenbuch entry exists for it:
     * `POST /api/z-bon/{id}/tip-payouts/settlements/{payoutId}/reverse`.
     */
    suspend fun reverseTipSettlement(
        zbonId: Long,
        payoutId: Long,
        request: ZBonDto.ReverseTipPayoutRequestDto,
    ): ZBonDto.TipPayoutResponseDto

    /** Who would sign a closing triggered from this device: `GET /api/z-bon/closing-readiness`. */
    suspend fun getClosingReadiness(): ZBonDto.ClosingReadinessDto
}
