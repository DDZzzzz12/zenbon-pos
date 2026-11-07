package org.example.project.core.domain.dto.monthlyclose

import kotlinx.serialization.Serializable
import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.statistics.PaymentMethodSummaryDto
import org.example.project.core.domain.dto.statistics.VatSummaryDto

object MonthlyCloseDto {
    @Serializable
    enum class SectionId { OVERVIEW, ZBON_ROLLUP, KASSENBUCH_ROLLUP, VAT, PAYMENTS, DISCREPANCIES, DATEV_EXPORT }

    @Serializable
    enum class ChecklistStatus { PASSED, WARNING, FAILED, HIDDEN }

    @Serializable
    enum class ExportBadgeStatus { NOT_EXPORTED, PARTIAL, EXPORTED }

    @Serializable
    data class KpiMetricDto(
        val key: String,
        val value: String,
        val dailySeries: List<String>,
        val previousValue: String?,
        val deltaPercent: String?,
    )

    /**
     * A readiness check result. [id] is the stable contract between server and client — the client
     * maps it to a localized [org.example.project.core.presentation.strings.StringKey]. The server
     * deliberately carries no label: a display string chosen server-side cannot be localized, and
     * emitting a key *name* leaks a developer identifier onto the operator's screen. The same holds
     * for the detail line, which travels as [detailCode] + [detailArgs].
     */
    @Serializable
    data class ChecklistItemDto(
        val id: String,
        val status: ChecklistStatus,
        /** English free text — kept for clients that predate [detailCode]; never rendered by one that knows it. */
        val detail: String,
        val affectedCount: Int,
        val jumpTo: SectionId,
        /**
         * What [detail] says, as a stable code from [MonthlyCloseDetailCodes]; the client renders
         * it localized from the code and [detailArgs]. `null` when there is nothing to say (or from
         * an older server, in which case the client falls back to [detail]).
         */
        val detailCode: String? = null,
        /** Positional arguments of [detailCode]; their meaning is documented per code. */
        val detailArgs: List<String> = emptyList(),
    )

    @Serializable
    data class ZBonRollupRowDto(
        val zBonId: Long,
        val zBonNumber: String,
        val startTime: String,
        val endTime: String,
        val grossRevenue: String,
        val netRevenue: String,
        val vatTotal: String,
        val tips: String,
        /** The closing's stored Storno total ("Rückerstattungen"), Zahlartwechsel Stornos included. */
        val refunds: String,
        val cancellations: String,
        val paymentMethodTotals: List<PaymentDto.PaymentSummary>,
        /**
         * "davon Zahlartwechsel": the part of [refunds] that is the Storno of a Storno + Neubuchung
         * (stored `rebook_storno_amount`). It refunds nothing, so `refunds − rebookStornos` is the
         * money handed back — the figure the `refunds` KPI shows (admin18 NOTE-20).
         */
        val rebookStornos: String = "0.00",
        /**
         * The Umsatz this closing's own bills give under today's Z-Bon rule
         * (`ZBonRevenueRule.posRevenueOf`). Equals [grossRevenue] for every closing made since 1.1.0.
         */
        val revenueFromBills: String = grossRevenue,
        /**
         * [grossRevenue] − [revenueFromBills]. Non-zero on a pre-1.1.0 closing whose stored Umsatz
         * still holds its promotions (admin18 NOTE-21); the stored closing is never rewritten.
         */
        val storedRevenueDifference: String = "0.00",
    )

    @Serializable
    data class ZBonChainGapDto(
        val afterZBonId: Long?,
        val gapStart: String,
        val gapEnd: String,
        val hasBillsInGap: Boolean,
    )

    @Serializable
    data class ZBonRollupDto(
        val rows: List<ZBonRollupRowDto>,
        val totals: ZBonRollupRowDto,
        val chainGaps: List<ZBonChainGapDto>,
    )

    @Serializable
    data class KassenbuchRollupGroupDto(
        val entryType: String,
        val subtotal: String,
        val entries: List<KassenbuchDto.KassenbuchEntryResponseDto>,
    )

    @Serializable
    data class KassenbuchRollupDto(
        val openingBalance: String,
        val closingBalance: String,
        val groups: List<KassenbuchRollupGroupDto>,
    )

    @Serializable
    data class ExportStatusDto(
        val badgeStatus: ExportBadgeStatus,
        val lastExportedAt: String?,
        val log: List<DatevExportLogDto>,
    )

    @Serializable
    data class MonthlyCloseResponseDto(
        val periodStart: String,
        val periodEnd: String,
        val kpis: List<KpiMetricDto>,
        val previousPeriodStart: String?,
        val previousPeriodEnd: String?,
        val vatBreakdown: Map<String, VatSummaryDto>,
        val paymentBreakdown: Map<String, PaymentMethodSummaryDto>,
        val zBonRollup: ZBonRollupDto,
        val kassenbuchRollup: KassenbuchRollupDto,
        val checklist: List<ChecklistItemDto>,
        val exportStatus: ExportStatusDto,
        /**
         * ISO currency every amount above is in — the shop's default currency, the same one the
         * financial overview carries. The client formats money with it instead of a constant.
         * Defaults to EUR so a response from an older server still decodes.
         */
        val currency: String = "EUR",
    )
}
