package org.example.project.features.monthlyclose.domain.model

import org.example.project.core.domain.dto.statistics.PaymentMethodSummaryDto
import org.example.project.core.domain.dto.statistics.VatSummaryDto

/**
 * The part of a period's financial overview the month close reads — and nothing else. Amounts are
 * the decimal strings of the statistics wire, passed through unchanged; the two breakdown maps keep
 * their order and are shown as they are in the month-close response.
 */
data class MonthlyCloseFinancialOverview(
    /** ISO 4217 currency code of every amount. */
    val currency: String,
    val grossRevenue: String,
    val netRevenue: String,
    val totalRefunds: String,
    val totalTips: String,
    /** By VAT group; the month close sums `vatAmount` for its VAT KPI and the `vat_consistent` check. */
    val vatBreakdown: Map<String, VatSummaryDto>,
    val paymentMethodBreakdown: Map<String, PaymentMethodSummaryDto>,
    /** By ISO date; the month close sorts by key for the KPI sparklines. */
    val dailySummary: Map<String, MonthlyCloseDailyFinancials>,
)
