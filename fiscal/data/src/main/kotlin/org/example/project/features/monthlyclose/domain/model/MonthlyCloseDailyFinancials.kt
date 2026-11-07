package org.example.project.features.monthlyclose.domain.model

/** One day of [MonthlyCloseFinancialOverview.dailySummary]: the figures behind the KPI sparklines. */
data class MonthlyCloseDailyFinancials(
    val grossRevenue: String,
    val netRevenue: String,
    val vatAmount: String,
    val transactionCount: Int,
)
