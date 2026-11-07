package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

object StatisticsDashboardKeys {
    val totalRevenue = StringKey("statistics_dashboard.total_revenue")
    val billedRevenue = StringKey("statistics_dashboard.billed_revenue")
    val tips = StringKey("statistics_dashboard.tips")
    val canceled = StringKey("statistics_dashboard.canceled")
    val refunds = StringKey("statistics_dashboard.refunds")
    val openItems = StringKey("statistics_dashboard.open_items")
    val openAmount = StringKey("statistics_dashboard.open_amount")
    val discounts = StringKey("statistics_dashboard.discounts")
    val today = StringKey("statistics_dashboard.today")
    val billsLabel = StringKey("statistics_dashboard.bills_label")
    val tablesLabel = StringKey("statistics_dashboard.tables_label")
    val employeesLabel = StringKey("statistics_dashboard.employees_label")
    val sessionsLabel = StringKey("statistics_dashboard.sessions_label")
    val sortRecent = StringKey("statistics_dashboard.sort_recent")
    val sortOldest = StringKey("statistics_dashboard.sort_oldest")
    val sortHighest = StringKey("statistics_dashboard.sort_highest")
    val sortLowest = StringKey("statistics_dashboard.sort_lowest")
    val billsCount = StringKey("statistics_dashboard.bills_count")
    val tipsLabel = StringKey("statistics_dashboard.tips_label")
    val table = StringKey("statistics_dashboard.table")
    val logBillNumber = StringKey("statistics_dashboard.log_bill_number")
    val logEmployee = StringKey("statistics_dashboard.log_employee")
    val logPayment = StringKey("statistics_dashboard.log_payment")
    val logTips = StringKey("statistics_dashboard.log_tips")
    val logAmount = StringKey("statistics_dashboard.log_amount")
    val logTime = StringKey("statistics_dashboard.log_time")
    val noTransactions = StringKey("statistics_dashboard.no_transactions")
    val noTransactionsDescription = StringKey("statistics_dashboard.no_transactions_description")

    // KPI Bottom Sheet keys
    val revenueBreakdown = StringKey("statistics_dashboard.revenue_breakdown")
    val tipsDetail = StringKey("statistics_dashboard.tips_detail")
    val billsSummary = StringKey("statistics_dashboard.bills_summary")
    val cancellations = StringKey("statistics_dashboard.cancellations")
    val averagePerBill = StringKey("statistics_dashboard.average_per_bill")
    val topTipEarners = StringKey("statistics_dashboard.top_tip_earners")
    val billsByEmployee = StringKey("statistics_dashboard.bills_by_employee")
    val canceledItemsLabel = StringKey("statistics_dashboard.canceled_items_label")
    val totalCanceled = StringKey("statistics_dashboard.total_canceled")
    val reason = StringKey("statistics_dashboard.reason")
    val payments = StringKey("statistics_dashboard.payments")
    val tapForDetails = StringKey("statistics_dashboard.tap_for_details")
    val noCancellations = StringKey("statistics_dashboard.no_cancellations")

    // Payments bottom sheet keys
    val paymentsDetail = StringKey("statistics_dashboard.payments_detail")
    val sortBy = StringKey("statistics_dashboard.sort_by")

    // Training mode
    val trainingMode = StringKey("statistics_dashboard.training_mode")
    val paymentTypeFilter = StringKey("statistics_dashboard.payment_type_filter")
    val allPaymentTypes = StringKey("statistics_dashboard.all_payment_types")
    val ongoing = StringKey("statistics_dashboard.ongoing")

    // Date filter keys
    val sinceLastZBon = StringKey("statistics_dashboard.since_last_zbon")

    // NOTE: `today` already exists — do NOT add a duplicate
    val yesterday = StringKey("statistics_dashboard.yesterday")
    val thisWeek = StringKey("statistics_dashboard.this_week")
    val thisMonth = StringKey("statistics_dashboard.this_month")
    val customRange = StringKey("statistics_dashboard.custom_range")

    // Filter bar result line, per view mode ({0} = count, "50+" while more pages exist)
    val resultBills = StringKey("statistics_dashboard.result_bills")
    val resultTables = StringKey("statistics_dashboard.result_tables")
    val resultEmployees = StringKey("statistics_dashboard.result_employees")

    // Filter panel fields and applied-filter chips
    val sortLabel = StringKey("statistics_dashboard.sort_label")
    val chipEmployee = StringKey("statistics_dashboard.chip_employee")
    val chipSort = StringKey("statistics_dashboard.chip_sort")
    val chipPaymentTypes = StringKey("statistics_dashboard.chip_payment_types")
}
