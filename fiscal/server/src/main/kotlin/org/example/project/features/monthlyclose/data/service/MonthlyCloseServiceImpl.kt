package org.example.project.features.monthlyclose.data.service

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.config.TimezoneConfig
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.enums.setting.DatevAutoCadence
import org.example.project.core.domain.enums.setting.DatevExportCadence
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.repository.BillRepository
import org.example.project.features.kassenbuch.domain.repository.KassenbuchRepository
import org.example.project.features.kassenbuch.domain.service.KassenbuchService
import org.example.project.features.kassenbuch.mapper.toKassenbuchEntryResponseDto
import org.example.project.features.monthlyclose.data.service.checks.BillsOutsideZBonCheck
import org.example.project.features.monthlyclose.data.service.checks.CashMovementsDocumentedCheck
import org.example.project.features.monthlyclose.data.service.checks.ChainIntegrityCheck
import org.example.project.features.monthlyclose.data.service.checks.DatevExportedCheck
import org.example.project.features.monthlyclose.data.service.checks.OpenBillsCheck
import org.example.project.features.monthlyclose.data.service.checks.PossibleDoubleSignCheck
import org.example.project.features.monthlyclose.data.service.checks.SimulatedSignatureCheck
import org.example.project.features.monthlyclose.data.service.checks.TseTanrContinuityCheck
import org.example.project.features.monthlyclose.data.service.checks.UnsignedTseCheck
import org.example.project.features.monthlyclose.data.service.checks.VatConsistencyCheck
import org.example.project.features.monthlyclose.domain.model.MonthlyCloseDailyFinancials
import org.example.project.features.monthlyclose.domain.model.MonthlyCloseFinancialOverview
import org.example.project.features.monthlyclose.domain.service.MonthlyCloseDatevStatus
import org.example.project.features.monthlyclose.domain.service.MonthlyCloseFinancials
import org.example.project.features.monthlyclose.domain.service.MonthlyCloseService
import org.example.project.features.zBon.domain.ZBonOwnerTipRule
import org.example.project.features.zBon.domain.ZBonRevenueRule
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.features.zBon.domain.repository.ZBonRepository
import org.example.project.features.zBon.domain.service.ZBonService
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.toDecimalString
import org.jetbrains.exposed.v1.core.inList
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Computes month-over-month (or period-over-period) delta percentage.
 *
 * Returns a signed string such as "+25.00" or "-8.50".
 * Returns null when:
 *   - [prev] is null (comparison disabled)
 *   - either value is unparseable as a decimal
 *   - [prev] is zero (division by zero)
 */
@OptIn(ExperimentalTime::class)
internal fun computeDeltaPercent(
    curr: String,
    prev: String?,
): String? {
    if (prev == null) return null
    val c = curr.toBigDecimalOrNull() ?: return null
    val p = prev.toBigDecimalOrNull() ?: return null
    if (p.signum() == 0) return null
    val pct =
        c
            .subtract(p)
            .divide(p, 4, java.math.RoundingMode.HALF_UP)
            .multiply(java.math.BigDecimal(100))
    val scaled = pct.setScale(2, java.math.RoundingMode.HALF_UP)
    return (if (pct.signum() >= 0) "+" else "") + scaled.toPlainString()
}

@OptIn(ExperimentalTime::class)
class MonthlyCloseServiceImpl(
    private val financials: MonthlyCloseFinancials,
    private val zBonService: ZBonService,
    private val zBonRepository: ZBonRepository,
    private val billRepository: BillRepository,
    private val kassenbuchService: KassenbuchService,
    private val kassenbuchRepository: KassenbuchRepository,
    private val datevStatus: MonthlyCloseDatevStatus,
    private val chainIntegrityCheck: ChainIntegrityCheck,
    private val billsOutsideZBonCheck: BillsOutsideZBonCheck,
    private val vatConsistencyCheck: VatConsistencyCheck,
    private val openBillsCheck: OpenBillsCheck,
    private val unsignedTseCheck: UnsignedTseCheck,
    private val possibleDoubleSignCheck: PossibleDoubleSignCheck,
    private val tseTanrContinuityCheck: TseTanrContinuityCheck,
    private val simulatedSignatureCheck: SimulatedSignatureCheck,
    private val cashMovementsDocumentedCheck: CashMovementsDocumentedCheck,
    private val datevExportedCheck: DatevExportedCheck,
    private val configManager: IShopConfigurationManager,
) : MonthlyCloseService {
    override suspend fun getMonthlyClose(
        periodStart: LocalDateTime,
        periodEnd: LocalDateTime,
        employeeId: String,
        comparePreviousPeriod: Boolean,
        cadence: DatevExportCadence,
    ): MonthlyCloseDto.MonthlyCloseResponseDto {
        val startLdt: LocalDateTime = periodStart
        val endLdt: LocalDateTime = periodEnd

        // Shop-wide by definition (route capability MONTHLY_CLOSE): the port reads every employee's
        // bills, never only the requester's (E2E-2026-09-18 #9).
        val financial = financials.overview(startLdt, endLdt, employeeId)

        // Optional previous-period comparison
        val (prevFinancial, prevStart, prevEnd) =
            if (comparePreviousPeriod) {
                val (ps, pe) = previousPeriodFor(startLdt, endLdt)
                val prev = financials.overview(ps, pe, employeeId)
                Triple(prev, ps, pe)
            } else {
                Triple(null, null, null)
            }

        val kassenbuchRollup = buildKassenbuchRollup(startLdt, endLdt)
        val kpis = buildKpis(financial, prevFinancial, kassenbuchRollup.closingBalance)

        val zBonRollup = buildZBonRollup(startLdt, endLdt)

        // AUTO-only logs drive the checklist coverage check; all-source logs drive the UI display.
        val autoExportLogs = datevStatus.autoExportLogs(startLdt, endLdt)
        val allExportLogs = datevStatus.allExportLogs(startLdt, endLdt)

        // Run all 9 checklist checks
        // DB-accessing checks (open bills, TSE, cash documented) run inside dbQuery;
        // pure-data checks run outside since they operate on already-loaded data.
        val openBillsItem = dbQuery { openBillsCheck.run(startLdt, endLdt) }
        val unsignedTseItem = dbQuery { unsignedTseCheck.run(startLdt, endLdt) }
        // 2026-09-18 finding #1: SIGNED status with a simulated signature — invisible to all_tse_signed.
        val simulatedSignatureItem = dbQuery { simulatedSignatureCheck.run(startLdt, endLdt) }
        val cashDocumentedItem = dbQuery { cashMovementsDocumentedCheck.run(startLdt, endLdt) }
        // M7: a lost TSE response may have produced a second signature for a Vorgang.
        val possibleDoubleSignItem = dbQuery { possibleDoubleSignCheck.run(startLdt, endLdt) }
        // The TANR register: numbers the device issued that the POS cannot account for.
        val tanrContinuityItem = dbQuery { tseTanrContinuityCheck.run(startLdt, endLdt) }

        val checklist: List<MonthlyCloseDto.ChecklistItemDto> =
            listOf(
                chainIntegrityCheck.run(zBonRollup),
                billsOutsideZBonCheck.run(zBonRollup),
                vatConsistencyCheck.run(financial, zBonRollup),
                openBillsItem,
                unsignedTseItem,
                simulatedSignatureItem,
                possibleDoubleSignItem,
                tanrContinuityItem,
                cashDocumentedItem,
                datevExportedCheck.run(startLdt, endLdt, cadence, autoExportLogs, apLedgerRequired(startLdt, endLdt)),
            )

        val exportStatus = buildExportStatus(startLdt, endLdt, allExportLogs)

        return MonthlyCloseDto.MonthlyCloseResponseDto(
            periodStart = startLdt.toString(),
            periodEnd = endLdt.toString(),
            kpis = kpis,
            previousPeriodStart = prevStart?.toString(),
            previousPeriodEnd = prevEnd?.toString(),
            vatBreakdown = financial.vatBreakdown,
            paymentBreakdown = financial.paymentMethodBreakdown,
            zBonRollup = zBonRollup,
            kassenbuchRollup = kassenbuchRollup,
            checklist = checklist,
            exportStatus = exportStatus,
            currency = financial.currency,
        )
    }

    // ---------------------------------------------------------------------------
    // Task 12 — Export status builder
    // ---------------------------------------------------------------------------

    private fun buildExportStatus(
        start: LocalDateTime,
        end: LocalDateTime,
        logs: List<org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto>,
    ): MonthlyCloseDto.ExportStatusDto {
        val coveredFully: (String) -> Boolean = { type ->
            logs.any { log ->
                log.exportType == type &&
                    LocalDateTime.parse(log.periodStart) <= start &&
                    LocalDateTime.parse(log.periodEnd) >= end
            }
        }
        val kassenbuch = coveredFully("KASSENBUCH")
        val ap = coveredFully("AP_LEDGER")
        val badge =
            when {
                kassenbuch && ap -> MonthlyCloseDto.ExportBadgeStatus.EXPORTED
                logs.isEmpty() -> MonthlyCloseDto.ExportBadgeStatus.NOT_EXPORTED
                else -> MonthlyCloseDto.ExportBadgeStatus.PARTIAL
            }
        return MonthlyCloseDto.ExportStatusDto(
            badgeStatus = badge,
            lastExportedAt = logs.maxByOrNull { it.exportedAt }?.exportedAt,
            log = logs.sortedByDescending { it.exportedAt },
        )
    }

    // ---------------------------------------------------------------------------
    // Task 10 — KPI builder + previous-period helper
    // ---------------------------------------------------------------------------

    /**
     * Computes the previous period of the same duration as [start]..[end].
     * Uses Instant briefly for duration arithmetic (LocalDateTime has no toEpochMs),
     * then converts back to LocalDateTime via the shop timezone.
     */
    internal fun previousPeriodFor(
        start: LocalDateTime,
        end: LocalDateTime,
    ): Pair<LocalDateTime, LocalDateTime> {
        val tz = TimezoneConfig.getApplicationTimeZone()
        // Duration-only Instant detour — not persisted or shipped on the wire
        val durationMs = end.toInstant(tz).toEpochMilliseconds() - start.toInstant(tz).toEpochMilliseconds()
        val prevEnd = start
        val prevStart =
            Instant
                .fromEpochMilliseconds(start.toInstant(tz).toEpochMilliseconds() - durationMs)
                .toLocalDateTime(tz)
        return prevStart to prevEnd
    }

    /**
     * AP_LEDGER is required only when the AP auto-export is on and the period holds creditor
     * invoices. "Has invoices" is asked of the AP export's own statistics query, with the export's
     * default Kassenbuch-link exclusion, so it can never disagree with what that export would write
     * (E2E-2026-09-18 #10).
     */
    private suspend fun apLedgerRequired(
        start: LocalDateTime,
        end: LocalDateTime,
    ): Boolean {
        if (configManager.getDatevApLedgerCadence() == DatevAutoCadence.OFF) return false
        // The AP export takes inclusive ISO dates; a period ending exactly at midnight ends the day before.
        val lastDay = if (end.hour == 0 && end.minute == 0 && end.second == 0) end.date.minus(DatePeriod(days = 1)) else end.date
        return datevStatus.apInvoiceCount(start.date.toString(), lastDay.toString()) > 0
    }

    internal fun buildKpis(
        financial: MonthlyCloseFinancialOverview,
        previous: MonthlyCloseFinancialOverview?,
        kassenbuchClosing: String,
    ): List<MonthlyCloseDto.KpiMetricDto> {
        val daily = financial.dailySummary.entries.sortedBy { it.key }

        fun seriesFor(extract: (MonthlyCloseDailyFinancials) -> String): List<String> = daily.map { extract(it.value) }

        return listOf(
            // Bill-derived (Σ paid revenue), also for closed periods — deliberately NOT Σ stored
            // totalPosRevenue: a pre-1.1.0 closing's stored Umsatz still holds its promotions, which
            // its own Netto + MwSt and payments do not (ZBonRevenueRule). The Z-Bon roll-up shows the
            // stored figure and the difference beside it (admin18 NOTE-21).
            MonthlyCloseDto.KpiMetricDto(
                key = "grossRevenue",
                value = financial.grossRevenue,
                dailySeries = seriesFor { it.grossRevenue },
                previousValue = previous?.grossRevenue,
                deltaPercent = computeDeltaPercent(financial.grossRevenue, previous?.grossRevenue),
            ),
            MonthlyCloseDto.KpiMetricDto(
                key = "netRevenue",
                value = financial.netRevenue,
                dailySeries = seriesFor { it.netRevenue },
                previousValue = previous?.netRevenue,
                deltaPercent = computeDeltaPercent(financial.netRevenue, previous?.netRevenue),
            ),
            MonthlyCloseDto.KpiMetricDto(
                key = "vatTotal",
                value =
                    financial.vatBreakdown.values
                        .sumOf { it.vatAmount.toBigDecimal() }
                        .toPlainString(),
                dailySeries = seriesFor { it.vatAmount },
                previousValue =
                    previous
                        ?.vatBreakdown
                        ?.values
                        ?.sumOf { it.vatAmount.toBigDecimal() }
                        ?.toPlainString(),
                deltaPercent = null, // computed client-side or left null per plan
            ),
            MonthlyCloseDto.KpiMetricDto(
                key = "billCount",
                value = daily.sumOf { it.value.transactionCount }.toString(),
                dailySeries = seriesFor { it.transactionCount.toString() },
                previousValue =
                    previous
                        ?.dailySummary
                        ?.values
                        ?.sumOf { it.transactionCount }
                        ?.toString(),
                deltaPercent = null,
            ),
            // Money handed back: Stornos WITHOUT the Zahlartwechsel ones (refundingStornoOp), whose
            // sale is booked again at once. The Z-Bon roll-up's `refunds` is the stored fiscal Storno
            // total incl. them, with `rebookStornos` as its "davon Zahlartwechsel", so
            // roll-up refunds − rebookStornos is this figure (admin18 NOTE-20).
            MonthlyCloseDto.KpiMetricDto(
                key = "refunds",
                value = financial.totalRefunds,
                dailySeries = emptyList(),
                previousValue = previous?.totalRefunds,
                deltaPercent = computeDeltaPercent(financial.totalRefunds, previous?.totalRefunds),
            ),
            MonthlyCloseDto.KpiMetricDto(
                key = "tips",
                value = financial.totalTips,
                dailySeries = emptyList(),
                previousValue = previous?.totalTips,
                deltaPercent = computeDeltaPercent(financial.totalTips, previous?.totalTips),
            ),
            MonthlyCloseDto.KpiMetricDto(
                key = "kassenbuchClosing",
                // The fiscal Ladenkasse's balance at period end — not cashFlow.closingBalance, which is
                // the window's cash takings and never a drawer balance (E2E-2026-09-18 #9).
                value = kassenbuchClosing,
                dailySeries = emptyList(),
                previousValue = null,
                deltaPercent = null,
            ),
        )
    }

    // ---------------------------------------------------------------------------
    // Task 11 — ZBon roll-up + chain-gap detection
    // ---------------------------------------------------------------------------

    private suspend fun buildZBonRollup(
        start: LocalDateTime,
        end: LocalDateTime,
    ): MonthlyCloseDto.ZBonRollupDto =
        dbQuery {
            val zBons = zBonRepository.findClosedInWindow(start, end)
            val billsByZBon = billsOf(zBons)

            val rows =
                zBons.map { z ->
                    val grossRevenue = z.totalPosRevenue
                    // The stored Umsatz is the fiscal record and is shown as stored. Beside it, what
                    // the closing's own bills give under today's rule: a pre-1.1.0 closing kept its
                    // promotions inside totalPosRevenue (ZBonRevenueRule), so the two differ there by
                    // exactly those promotions, and the bill-derived Umsatz tile agrees with the
                    // second figure, not the first (admin18 NOTE-21).
                    val revenueFromBills = ZBonRevenueRule.posRevenueOf(billsByZBon[z.id.value].orEmpty())
                    // netRevenue = sum of net amounts across all VAT groups
                    val netRevenue = z.vatGroups.sumOf { it.netAmount }
                    val vatTotal = z.vatGroups.sumOf { it.vatAmount }
                    val paymentTotals =
                        z.paymentSummaries.map { ps ->
                            PaymentDto.PaymentSummary(
                                amount = ps.amount.toDecimalString(),
                                paymentTypeDE = ps.paymentType.name,
                            )
                        }

                    // Every tip of the closing: the employee summaries carry the employee tips and
                    // adminTip the owner tip. A legacy closing's ADMIN summaries ALSO carried the
                    // owner tip, so adding adminTip there would count it twice (ZBonOwnerTipRule).
                    val summaryTips = z.employeeSummaries.sumOf { it.tipAmount }
                    val totalTips = if (ZBonOwnerTipRule.isLegacyClosing(z)) summaryTips else summaryTips + z.adminTip

                    MonthlyCloseDto.ZBonRollupRowDto(
                        zBonId = z.id.value,
                        zBonNumber = z.zbonNummer.toString(),
                        startTime = z.startTime.toString(),
                        endTime = z.endTime.toString(),
                        grossRevenue = grossRevenue.toDecimalString(),
                        netRevenue = netRevenue.toDecimalString(),
                        vatTotal = vatTotal.toDecimalString(),
                        tips = totalTips.toDecimalString(),
                        refunds = z.refundAmount.toDecimalString(),
                        cancellations = z.cancelledAmount.toDecimalString(),
                        paymentMethodTotals = paymentTotals,
                        rebookStornos = z.rebookStornoAmount.toDecimalString(),
                        revenueFromBills = revenueFromBills.toDecimalString(),
                        storedRevenueDifference = (grossRevenue - revenueFromBills).toDecimalString(),
                    )
                }

            val totals =
                MonthlyCloseDto.ZBonRollupRowDto(
                    zBonId = 0L,
                    zBonNumber = "TOTAL",
                    startTime = start.toString(),
                    endTime = end.toString(),
                    grossRevenue = rows.sumOf { it.grossRevenue.toBigDecimal() }.toDecimalString(),
                    netRevenue = rows.sumOf { it.netRevenue.toBigDecimal() }.toDecimalString(),
                    vatTotal = rows.sumOf { it.vatTotal.toBigDecimal() }.toDecimalString(),
                    tips = rows.sumOf { it.tips.toBigDecimal() }.toDecimalString(),
                    refunds = rows.sumOf { it.refunds.toBigDecimal() }.toDecimalString(),
                    cancellations = rows.sumOf { it.cancellations.toBigDecimal() }.toDecimalString(),
                    paymentMethodTotals = emptyList(),
                    rebookStornos = rows.sumOf { it.rebookStornos.toBigDecimal() }.toDecimalString(),
                    revenueFromBills = rows.sumOf { it.revenueFromBills.toBigDecimal() }.toDecimalString(),
                    storedRevenueDifference = rows.sumOf { it.storedRevenueDifference.toBigDecimal() }.toDecimalString(),
                )

            // Chain-gap detection
            val gaps = mutableListOf<MonthlyCloseDto.ZBonChainGapDto>()
            val priorZ = zBonRepository.findLastClosedBefore(start)
            val firstSortedStartTime = zBons.firstOrNull()?.startTime

            // Boundary gap: prior Z-Bon exists but its end < first Z-Bon's start (or period start)
            val boundaryGapEnd = firstSortedStartTime ?: end
            if (priorZ == null || priorZ.endTime < boundaryGapEnd) {
                val gapStart = priorZ?.endTime ?: start
                if (gapStart < boundaryGapEnd) {
                    val hasBills = billRepository.existsClosedBillsBetween(gapStart, boundaryGapEnd)
                    gaps +=
                        MonthlyCloseDto.ZBonChainGapDto(
                            afterZBonId = priorZ?.id?.value,
                            gapStart = gapStart.toString(),
                            gapEnd = boundaryGapEnd.toString(),
                            hasBillsInGap = hasBills,
                        )
                }
            }

            // Internal gaps: adjacent Z-Bon pairs where curr.startTime > prev.endTime
            for (i in 1 until zBons.size) {
                val prev = zBons[i - 1]
                val curr = zBons[i]
                if (curr.startTime > prev.endTime) {
                    val hasBills = billRepository.existsClosedBillsBetween(prev.endTime, curr.startTime)
                    gaps +=
                        MonthlyCloseDto.ZBonChainGapDto(
                            afterZBonId = prev.id.value,
                            gapStart = prev.endTime.toString(),
                            gapEnd = curr.startTime.toString(),
                            hasBillsInGap = hasBills,
                        )
                }
            }

            MonthlyCloseDto.ZBonRollupDto(rows = rows, totals = totals, chainGaps = gaps)
        }

    /** Every bill linked to [zBons], by closing id, in one query. Requires an ambient transaction. */
    private fun billsOf(zBons: List<ZBon>): Map<Long?, List<Bill>> {
        if (zBons.isEmpty()) return emptyMap()
        return Bill
            .find { BillTable.zBonId inList zBons.map { it.id } }
            .toList()
            .groupBy { bill -> bill.readValues[BillTable.zBonId]?.value }
    }

    // ---------------------------------------------------------------------------
    // Task 11 — Kassenbuch roll-up
    // ---------------------------------------------------------------------------

    private suspend fun buildKassenbuchRollup(
        start: LocalDateTime,
        end: LocalDateTime,
    ): MonthlyCloseDto.KassenbuchRollupDto {
        // The fiscal Ladenkasse only: the Tresor and other books are not the till's cash, and a
        // balance is per book, never across books (E2E-2026-09-18 #9).
        val openingBalance = kassenbuchService.getFiscalBookBalanceAt(start)
        val closingBalance = kassenbuchService.getFiscalBookBalanceAt(end)
        val headers = kassenbuchService.findFiscalBookEntriesInWindow(start, end)

        val groups =
            dbQuery {
                headers
                    .groupBy { it.entryType }
                    .map { (entryType, headerList) ->
                        val subtotal = headerList.sumOf { it.totalAmount }
                        val entryDtos = headerList.map { it.toKassenbuchEntryResponseDto() }
                        MonthlyCloseDto.KassenbuchRollupGroupDto(
                            entryType = entryType.name,
                            subtotal = subtotal.toDecimalString(),
                            entries = entryDtos,
                        )
                    }.sortedBy { it.entryType }
            }

        return MonthlyCloseDto.KassenbuchRollupDto(
            openingBalance = openingBalance.toDecimalString(),
            closingBalance = closingBalance.toDecimalString(),
            groups = groups,
        )
    }
}
