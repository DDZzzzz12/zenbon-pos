package org.example.project.features.monthlyclose.domain.service

import kotlinx.datetime.LocalDateTime
import org.example.project.features.monthlyclose.domain.model.MonthlyCloseFinancialOverview

/**
 * The bill-derived figures the fiscal month close (Monatsabschluss) shows and checks: its KPIs and
 * their daily series, the VAT and payment breakdowns, and the VAT total the `vat_consistent` check
 * compares with the Z-Bon roll-up.
 *
 * A port, declared on the fiscal side and implemented by the backoffice statistics, so the month
 * close does not depend on the statistics service or its `StatisticsDto`. The month close calls it
 * once for the requested period and, with the previous-period comparison, once more for the period
 * of the same length before it.
 */
interface MonthlyCloseFinancials {
    /**
     * The financial overview of [periodStart]..[periodEnd] as [employeeId] asks for it, **shop-wide**:
     * the Monatsabschluss covers every employee's bills, never only the requester's
     * (E2E-2026-09-18 #9). Live bills only, no training bills.
     */
    suspend fun overview(
        periodStart: LocalDateTime,
        periodEnd: LocalDateTime,
        employeeId: String,
    ): MonthlyCloseFinancialOverview
}
