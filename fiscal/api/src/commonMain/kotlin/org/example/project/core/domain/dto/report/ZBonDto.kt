package org.example.project.core.domain.dto.report

import kotlinx.serialization.Serializable
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.domain.enums.zBon.ClosingSignerProblem
import org.example.project.core.domain.enums.zBon.TipPayoutMethod

/**
 * Container object for all ZBon (Z-report) related DTOs.
 *
 * Organizes DTOs by functionality: requests, responses, and analytics
 * to maintain clear separation of concerns and consistent API structure.
 * Handles daily closing reports and financial reconciliation data.
 */
object ZBonDto {
    /**
     * Optional body of `POST /api/z-bon`. [countedAmount] is the drawer count (dot decimal) or null.
     * The server books the Kassendifferenz only once the closing's preconditions pass, so a refused
     * closing leaves the book untouched (E2E-2026-09-18 #8).
     *
     * [tipPayouts] (F58) are employee-tip payouts booked INSIDE this closing: each is a signed
     * Kassenbuch withdrawal of the employee's TrinkgeldAN from the period being closed, booked in the
     * closing's own transaction, so it belongs to this closing — or, if the closing is refused,
     * nothing is booked at all. Each amount is ≤ that employee's employee tips in the period; one
     * entry per employee. Empty (the default, and what an older till sends) closes exactly as before.
     * Requires `CASHBOOK_WRITE` on top of `ZBON_CLOSE`. The count is booked in the same transaction,
     * after the payouts, and the daily Geldtransit moves the cash takings minus these payouts.
     * A payout with method `BANK_PAYROLL` moves no cash: it only settles the open amount.
     *
     * [topUp] (F59, option A) tops the drawer up from another cash book when the CASH payouts exceed
     * what it holds: a Geldtransit from that book into the fiscal Ladenkasse, booked inside the same
     * closing BEFORE the payouts, through the ordinary transfer legs, guard and signing. Null (the
     * default) books none. Requires cash payouts to be present and `CASHBOOK_WRITE`.
     */
    @Serializable
    data class CreateZBonRequestDto(
        val countedAmount: String? = null,
        val tipPayouts: List<CreateTipPayoutRequestDto> = emptyList(),
        val topUp: ClosingTopUpRequestDto? = null,
    )

    /**
     * Part of [CreateZBonRequestDto]: put [amount] (a positive exact decimal string) from cash book
     * [fromCashBookId] — a non-fiscal, active book of the shop (Tresor, Hauptkasse) holding at least
     * that much — into the fiscal Ladenkasse inside the closing, before the tip payouts.
     */
    @Serializable
    data class ClosingTopUpRequestDto(
        val fromCashBookId: Long,
        val amount: String,
    )

    /**
     * `GET /api/z-bon/closing-readiness`: whether a closing triggered from the calling device could
     * book cash movements, and who would sign them.
     *
     * @property signerName the till that would sign (designated, else the caller), or null when none
     * @property signerProblem null when [signerName] can sign; otherwise why not
     * @property tseDisabled the TSE mode is DISABLED: nothing is signed, no signing till is needed
     * @property dailyTransferConfigured a daily Geldtransit target is set, so every closing books
     *   one and therefore needs a signer
     */
    @Serializable
    data class ClosingReadinessDto(
        val signerName: String? = null,
        val signerProblem: ClosingSignerProblem? = null,
        val dailyTransferConfigured: Boolean = false,
        /** The shop's currency (ISO 4217). Blank means unknown (an older server) — callers fall back. */
        val currency: String = "",
        /** The TSE is disabled: nothing is signed, so no signing till is needed (an older server omits it: false). */
        val tseDisabled: Boolean = false,
    ) {
        /** Cash movements (count, payouts, top-up) may be booked. */
        val movementsAllowed: Boolean get() = signerProblem == null

        /** Not even a plain closing is possible: the daily Geldtransit needs a signer that is missing. */
        val blocksClosing: Boolean get() = signerProblem != null && dailyTransferConfigured
    }

    /**
     * One employee's employee tips (TrinkgeldAN) in the still-open period — the bills the next
     * Z-Bon will close, scoped exactly as that closing scopes them. `GET /api/z-bon/open-period-tips`.
     *
     * [cashTip] + [cardTip] = [tipTotal]. The split is display only: the payout always pays the total
     * in cash from the drawer, which advances the card part (it reaches the bank via card settlement).
     * Per bill, the card part is the tip up to the bill's non-cash tender; the rest is cash.
     */
    @Serializable
    data class OpenPeriodTipDto(
        val employeeId: String,
        val employeeNumber: String,
        val employeeName: String,
        val tipTotal: String,
        val cashTip: String,
        val cardTip: String,
    )

    @Serializable
    data class ZBonSummeryDto(
        val id: Long,
        val zbonNummer: Long = 0,
        val startTime: String,
        val endTime: String,
        val totalRevenue: String,
        /**
         * ISO 4217 code the revenue total is denominated in. Supplied by the server from shop
         * config (`ConfigKey.DEFAULT_CURRENCY`), resolved once per request by the service. The
         * default only keeps non-server construction sites compiling.
         */
        val currency: String = "EUR",
    )

    /**
     * Response DTO for ZBon data.
     *
     * Contains complete Z-report information for API responses including
     * financial summaries, timestamps, and related entity information.
     */
    @Serializable
    data class ZBonResponseDto(
        val id: Long,
        val zbonNummer: Long = 0,
        val shopInfoId: Long,
        val shopName: String,
        val shopAddress: String = "",
        val shopCity: String = "",
        val shopZipCode: String = "",
        val shopCountry: String = "",
        val shopTaxNumber: String = "",
        val employeeNumber: String,
        val startTime: String,
        val endTime: String,
        val cancelledAmount: String,
        val refundAmount: String = "0.00",
        val stornoBillCount: Int = 0,
        /** Number of Zahlartwechsel Storno bills (Storno + Neubuchung) in this Z-report period. */
        val rebookStornoCount: Int = 0,
        /** Total amount of Zahlartwechsel Storno bills in this Z-report period. */
        val rebookStornoAmount: String = "0.00",
        val totalDiscounts: String = "0.00",
        val totalPromotions: String = "0.00",
        val promotionBreakdown: List<PromotionBreakdownDto>? = emptyList(),
        /**
         * The owner tips of the period (TrinkgeldAG): taxed turnover, already inside [totalRevenue]
         * and the VAT groups. Never part of [totalEmployeeTips].
         */
        val adminTip: String? = null,
        val totalRevenue: String,
        /** 4c-2c: gross of receivables cleared at the till, separate from revenue. */
        val totalReceivableCleared: String = "0.00",
        val openingBalance: String,
        val expenses: String,
        val closingBalance: String,
        val createdAt: String,
        val vatGroups: List<VatGroupResponseDto>? = emptyList(),
        val paymentSummary: List<PaymentsSummaryResponseDto>? = emptyList(),
        val employeeSummaries: List<EmployeeZBonSummaryResponseDto>? = emptyList(),
        val vouchers: List<VoucherDto.VoucherSummeryDto>? = emptyList(),
        val voucherRedeems: List<VoucherDto.RedeemVoucherResponse>? = emptyList(),
        /**
         * Σ [EmployeeZBonSummaryResponseDto.amount] over the summaries whose tip is NOT an owner tip
         * ([EmployeeZBonSummaryResponseDto.ownerTip] false) — the employee tips (TrinkgeldAN), which
         * are not revenue. Null when there is no such summary. A tip block that lists the
         * employee summaries must total to exactly this; the owner tip is [adminTip].
         */
        val totalEmployeeTips: String? = null,
        val trainingRevenue: String = "0.00",
        val trainingBillCount: Int = 0,
        val kassenbuchSummaryDto: List<KassenbuchDto.KassenbuchGroupedSummaryDto>? = null,
        /**
         * Per employee with employee tips (TrinkgeldAN) in this closing: how much was tipped, how
         * much has been paid out from the drawer since, and what is still payable. Filled on the
         * single-report reads (`GET /api/z-bon/{id}`, the create response); empty on list reads and
         * from an older server, so a client must treat "empty" as "nothing to pay out here".
         */
        val tipPayouts: List<EmployeeTipPayoutStatusDto> = emptyList(),
    )

    /**
     * One employee's employee-tip position in one closing. Money as exact decimal strings.
     *
     * @property employeeId the employee's UUID — the key `POST /api/z-bon/{id}/tip-payouts` takes
     * @property tipTotal Σ of the employee's TrinkgeldAN in the closing, cash AND card
     * @property paidOut Σ of the payouts already booked against this closing
     * @property paidOut Σ payouts minus Σ reversals of them
     * @property remaining [tipTotal] − [paidOut]; never negative — the server refuses a payout
     *   that would make it so
     */
    @Serializable
    data class EmployeeTipPayoutStatusDto(
        val employeeId: String,
        val employeeNumber: String,
        val employeeName: String,
        val tipTotal: String,
        val paidOut: String,
        val remaining: String,
        /** The payouts booked against this closing for the employee, oldest first. Empty from an older server. */
        val payouts: List<TipPayoutRecordDto> = emptyList(),
    )

    /**
     * One booked tip payout. [reversed] is true once it has been stornoed — its amount is then open
     * again, and it cannot be reversed a second time.
     *
     * A [TipPayoutMethod.CASH] payout carries its Kassenbuch withdrawal ([kassenbuchHeaderId],
     * [belegNr]) and is stornoed through `…/tip-payouts/{kassenbuchHeaderId}/reverse`. A
     * [TipPayoutMethod.BANK_PAYROLL] settlement has no Kassenbuch entry — both are null — and is
     * stornoed through `…/tip-payouts/settlements/{payoutId}/reverse`.
     */
    @Serializable
    data class TipPayoutRecordDto(
        val kassenbuchHeaderId: Long?,
        val belegNr: String?,
        val amount: String,
        val createdAt: String,
        val reversed: Boolean,
        val payoutId: Long = 0,
        val method: TipPayoutMethod = TipPayoutMethod.CASH,
    )

    /**
     * Body of `POST /api/z-bon/{id}/tip-payouts/{kassenbuchHeaderId}/reverse`: why the payout is
     * stornoed. Required and non-blank, like every Kassenbuch correction; printed on the Eigenbeleg.
     */
    @Serializable
    data class ReverseTipPayoutRequestDto(
        val reason: String,
    )

    /**
     * Body of `POST /api/z-bon/{id}/tip-payouts`: settle [amount] (a positive exact decimal string)
     * of [employeeId]'s tips from this closing. Partial payouts are allowed; their sum never exceeds
     * the employee's tip total in that closing. [method] says how: in cash from the drawer (the
     * default, and what an older till sends) or by bank transfer / payroll (no cash moves).
     */
    @Serializable
    data class CreateTipPayoutRequestDto(
        val employeeId: String,
        val amount: String,
        val method: TipPayoutMethod = TipPayoutMethod.CASH,
    )

    /**
     * Answer to a tip payout or its storno: the Kassenbuch entry it booked — null for a
     * [TipPayoutMethod.BANK_PAYROLL] settlement, which books none — the `employee_tip_payout` row,
     * and the employee's new position.
     */
    @Serializable
    data class TipPayoutResponseDto(
        val kassenbuchHeaderId: Long?,
        val belegNr: String?,
        val status: EmployeeTipPayoutStatusDto,
        val payoutId: Long? = null,
        val method: TipPayoutMethod = TipPayoutMethod.CASH,
    )

    @Serializable
    data class EmployeeZBonSummaryResponseDto(
        val id: Long = 0,
        val employeeNumber: String,
        val employeeName: String = "",
        /**
         * The employee's CURRENT role (and [employeeName] the current name), joined at read time —
         * neither is stored on the closing. No Z-Bon rendering shows either, so a closed Z-Bon
         * renders the same after a role change or rename (emu16 NOTE-4). Kept on the wire because
         * older tills parse it with `EmployeeRole.valueOf`.
         */
        val role: String,
        /**
         * The employee tips (TrinkgeldAN) on this employee's bills of the period, decided by each
         * bill's stored tip recipient. An owner tip is never included — it is [ZBonResponseDto.adminTip]
         * — except on closings created before that rule, see [ownerTip].
         */
        val amount: String,
        val totalRevenue: String = "0.00",
        val paymentBreakdown: List<EmployeeZBonPaymentBreakdownResponseDto> = emptyList(),
        /**
         * True when [amount] is an owner tip (TrinkgeldAG) — taxed turnover, already counted in
         * [ZBonResponseDto.adminTip] — rather than an employee tip. Only ever true on an ADMIN's
         * summary of a closing created before summaries stopped carrying owner tips (the server's
         * legacy rendering rule, `ZBonOwnerTipRule`). A client must not re-derive it from [role].
         * Defaults to false so an older server's response still decodes.
         */
        val ownerTip: Boolean = false,
    )

    /**
     * Response DTO for per-employee payment type breakdown.
     *
     * Shows how much revenue an employee received via a specific payment method.
     */
    @Serializable
    data class EmployeeZBonPaymentBreakdownResponseDto(
        val paymentName: String,
        val currency: String,
        val amount: String,
    )

    @Serializable
    data class VatGroupResponseDto(
        val vatRate: String,
        val vatClass: String,
        val netAmount: String,
        val vatAmount: String,
        val grossAmount: String,
    )

    @Serializable
    data class PaymentsSummaryResponseDto(
        val paymentName: String,
        val currency: String,
        val amount: String,
    )

    @Serializable
    data class PromotionBreakdownDto(
        val promotionId: Long,
        val promotionName: String,
        val discountType: String,
        val timesApplied: Int,
        val totalDeducted: String,
    )

    /**
     * DTO for Z-report summary statistics.
     *
     * Contains aggregated data for business analysis and reporting
     * across multiple Z-reports and time periods.
     */
    @Serializable
    data class ZBonSummaryDto(
        val totalReports: Int,
        val totalRevenue: String,
        val totalCancellations: String,
        val totalExpenses: String,
        val averageRevenuePerReport: String,
        val dateRange: String,
        val shopCount: Int,
        val employeeCount: Int,
        val reportsWithTse: Int,
        val reportsWithoutTse: Int,
    )

    /**
     * DTO for Z-report validation results.
     *
     * Contains validation status and detailed feedback for business
     * rule compliance and data integrity verification.
     */
    @Serializable
    data class ZBonValidationDto(
        val isValid: Boolean,
        val errors: List<String>,
        val warnings: List<String>,
        val businessRuleViolations: List<String>,
        val suggestedCorrections: List<String>,
    )

    /**
     * DTO for Z-report list responses.
     *
     * Contains paginated list of Z-reports with metadata for
     * efficient API responses and client-side handling.
     */
    @Serializable
    data class ZBonListResponseDto(
        val reports: List<ZBonResponseDto>,
        val totalCount: Int,
        val pageSize: Int,
        val currentPage: Int,
        val totalPages: Int,
        val hasNextPage: Boolean,
        val hasPreviousPage: Boolean,
    )

    /**
     * DTO for Z-report search filters.
     *
     * Contains search criteria for filtering Z-reports by various
     * business parameters and date ranges.
     */
    @Serializable
    data class ZBonSearchFiltersDto(
        val shopInfoId: Long? = null,
        val employeeId: String? = null,
        val startDate: String? = null,
        val endDate: String? = null,
        val minRevenue: String? = null,
        val maxRevenue: String? = null,
        val hasTseTransaction: Boolean? = null,
        val pageSize: Int = 50,
        val pageNumber: Int = 1,
    ) {
        fun validate() {
            require(pageSize > 0 && pageSize <= 1000) { "Page size must be between 1 and 1000" }
            require(pageNumber > 0) { "Page number must be positive" }

            minRevenue?.let { min ->
                try {
                    val minValue = min.toDouble()
                    require(minValue >= 0.0) { "Min revenue must be non-negative" }
                } catch (e: NumberFormatException) {
                    throw IllegalArgumentException("Min revenue must be a valid number")
                }
            }

            maxRevenue?.let { max ->
                try {
                    val maxValue = max.toDouble()
                    require(maxValue >= 0.toDouble()) { "Max revenue must be non-negative" }
                } catch (e: NumberFormatException) {
                    throw IllegalArgumentException("Max revenue must be a valid number")
                }
            }

            if (minRevenue != null && maxRevenue != null) {
                val minValue = minRevenue.toDouble()
                val maxValue = maxRevenue.toDouble()
                require(minValue <= maxValue) { "Min revenue must be less than or equal to max revenue" }
            }
        }
    }

    /**
     * DTO for checking period availability.
     *
     * Contains request parameters for validating whether a specific
     * time period is available for creating a new Z-report.
     */
    @Serializable
    data class CheckPeriodAvailabilityDto(
        val shopInfoId: Long,
        val startTime: String,
        val endTime: String,
    ) {
        fun validate() {
            require(shopInfoId > 0) { "Shop info ID must be positive" }
            require(startTime.isNotBlank()) { "Start time is required" }
            require(endTime.isNotBlank()) { "End time is required" }
        }
    }

    /**
     * DTO for period availability response.
     *
     * Contains result of period availability check with detailed
     * information about conflicts and recommendations.
     */
    @Serializable
    data class PeriodAvailabilityResponseDto(
        val isAvailable: Boolean,
        val conflictingReportId: Long?,
        val conflictingPeriod: String?,
        val suggestedStartTime: String?,
        val suggestedEndTime: String?,
    )
}
