package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

object MonthlyCloseKeys {
    // Tab / section titles
    val tabTitle = StringKey("monthlyClose.tab_title")
    val sectionOverview = StringKey("monthlyClose.section_overview")
    val sectionZBonRollup = StringKey("monthlyClose.section_zbon_rollup")
    val sectionKassenbuchRollup = StringKey("monthlyClose.section_kassenbuch_rollup")
    val sectionVat = StringKey("monthlyClose.section_vat")
    val sectionPayments = StringKey("monthlyClose.section_payments")
    val sectionDiscrepancies = StringKey("monthlyClose.section_discrepancies")
    val sectionDatevExport = StringKey("monthlyClose.section_datev_export")

    // Toolbar
    val toolbarRangeWeek = StringKey("monthlyClose.toolbar_range_week")
    val toolbarRangeMonth = StringKey("monthlyClose.toolbar_range_month")
    val toolbarRangeQuarter = StringKey("monthlyClose.toolbar_range_quarter")
    val toolbarRangeYtd = StringKey("monthlyClose.toolbar_range_ytd")
    val weekPickerLabel = StringKey("monthlyClose.week_picker_label")
    val toolbarExportButton = StringKey("monthlyClose.toolbar_export_button")
    val toolbarExportKassenbuch = StringKey("monthlyClose.toolbar_export_kassenbuch")
    val toolbarExportApLedger = StringKey("monthlyClose.toolbar_export_ap_ledger")
    val toolbarExportBuchungsstapelBelege = StringKey("monthlyClose.toolbar_export_buchungsstapel_belege")
    val toolbarExportBoth = StringKey("monthlyClose.toolbar_export_both")

    // Status badges
    val statusExported = StringKey("monthlyClose.status_exported")
    val statusPartial = StringKey("monthlyClose.status_partial")
    val statusNotExported = StringKey("monthlyClose.status_not_exported")

    // KPI labels
    val kpiGrossRevenue = StringKey("monthlyClose.kpi_gross_revenue")
    val kpiNetRevenue = StringKey("monthlyClose.kpi_net_revenue")
    val kpiVatTotal = StringKey("monthlyClose.kpi_vat_total")
    val kpiBillCount = StringKey("monthlyClose.kpi_bill_count")
    val kpiRefunds = StringKey("monthlyClose.kpi_refunds")
    val kpiTips = StringKey("monthlyClose.kpi_tips")
    val kpiKassenbuchClosing = StringKey("monthlyClose.kpi_kassenbuch_closing")

    // Checklist
    val checkZBonChainIntegrity = StringKey("monthlyClose.check_zbon_chain_integrity")
    val checkBillsOutsideZBon = StringKey("monthlyClose.check_bills_outside_zbon")
    val checkVatConsistent = StringKey("monthlyClose.check_vat_consistent")
    val checkNoOpenBills = StringKey("monthlyClose.check_no_open_bills")
    val checkAllTseSigned = StringKey("monthlyClose.check_all_tse_signed")
    val checkAllCashDocumented = StringKey("monthlyClose.check_all_cash_documented")
    val checkDatevExported = StringKey("monthlyClose.check_datev_exported")
    val checkNoPossibleDoubleSign = StringKey("monthlyClose.check_no_possible_double_sign")
    val checkTseTanrContinuity = StringKey("monthlyClose.check_tse_tanr_continuity")
    val checkNoSimulatedSignatures = StringKey("monthlyClose.check_no_simulated_signatures")

    // Checklist detail lines, rendered from ChecklistItemDto.detailCode + detailArgs ({0}, {1}, …)
    val detailZBonOverlaps = StringKey("monthlyClose.detail_zbon_overlaps")
    val detailBillsOutsideZBon = StringKey("monthlyClose.detail_bills_outside_zbon")
    val detailVatTotalOnly = StringKey("monthlyClose.detail_vat_total_only")
    val detailVatDelta = StringKey("monthlyClose.detail_vat_delta")
    val detailOpenBills = StringKey("monthlyClose.detail_open_bills")
    val detailUnsignedBills = StringKey("monthlyClose.detail_unsigned_bills")
    val detailUndocumentedCashMovements = StringKey("monthlyClose.detail_undocumented_cash_movements")
    val detailDatevMissingPeriod = StringKey("monthlyClose.detail_datev_missing_period")
    val detailDatevMissingWeeks = StringKey("monthlyClose.detail_datev_missing_weeks")
    val detailPossibleDoubleSign = StringKey("monthlyClose.detail_possible_double_sign")
    val detailSimulatedSignatures = StringKey("monthlyClose.detail_simulated_signatures")
    val detailUnexplainedTseRanges = StringKey("monthlyClose.detail_unexplained_tse_ranges")
    val exportTypeKassenbuch = StringKey("monthlyClose.export_type_kassenbuch")
    val exportTypeApLedger = StringKey("monthlyClose.export_type_ap_ledger")
    val exportTypeArLedger = StringKey("monthlyClose.export_type_ar_ledger")
    val exportTypeBuchungsstapel = StringKey("monthlyClose.export_type_buchungsstapel")
    val exportTypeBuchungsstapelBelege = StringKey("monthlyClose.export_type_buchungsstapel_belege")

    // Timeline
    val timelineCaption = StringKey("monthlyClose.timeline_caption")

    // ZBon Rollup column headers
    val colZBonNumber = StringKey("monthlyClose.col_zbon_number")
    val colStartTime = StringKey("monthlyClose.col_start_time")
    val colEndTime = StringKey("monthlyClose.col_end_time")
    val colGrossRevenue = StringKey("monthlyClose.col_gross_revenue")
    val colNetRevenue = StringKey("monthlyClose.col_net_revenue")
    val colVatTotal = StringKey("monthlyClose.col_vat_total")
    val colTips = StringKey("monthlyClose.col_tips")
    val colRefunds = StringKey("monthlyClose.col_refunds")
    val colCancellations = StringKey("monthlyClose.col_cancellations")

    /** "davon Zahlartwechsel" — the part of [colRefunds] that refunds nothing (admin18 NOTE-20). */
    val colRebookStornos = StringKey("monthlyClose.col_rebook_stornos")

    /**
     * The reconciliation line under the Z-Bon roll-up when stored Umsatz ≠ bill-derived Umsatz
     * (admin18 NOTE-21). {0} = Σ stored gross, {1} = Σ from the bills, {2} = the difference,
     * {3} = the affected Z-Bon numbers, comma-separated.
     */
    val revenueReconciliation = StringKey("monthlyClose.revenue_reconciliation")
    val totalsRowLabel = StringKey("monthlyClose.totals_row_label")

    // Kassenbuch Rollup
    val openingBalanceLabel = StringKey("monthlyClose.opening_balance_label")
    val closingBalanceLabel = StringKey("monthlyClose.closing_balance_label")

    // VAT section column headers
    val colVatRate = StringKey("monthlyClose.col_vat_rate")
    val colNetAmount = StringKey("monthlyClose.col_net_amount")
    val colVatAmount = StringKey("monthlyClose.col_vat_amount")
    val colTotalAmount = StringKey("monthlyClose.col_total_amount")
    val colTransactionCount = StringKey("monthlyClose.col_transaction_count")

    // Payments section column headers
    val colPaymentMethod = StringKey("monthlyClose.col_payment_method")
    val colRevenuePercent = StringKey("monthlyClose.col_revenue_percent")
    val colAvgTransaction = StringKey("monthlyClose.col_avg_transaction")

    // Discrepancies section
    val noDiscrepanciesTitle = StringKey("monthlyClose.no_discrepancies_title")
    val noDiscrepanciesBody = StringKey("monthlyClose.no_discrepancies_body")
    val affectedCountLabel = StringKey("monthlyClose.affected_count_label")

    // DATEV Export section
    val exportLogEmpty = StringKey("monthlyClose.export_log_empty")
    val colExportType = StringKey("monthlyClose.col_export_type")
    val colPeriod = StringKey("monthlyClose.col_period")
    val colExportedAt = StringKey("monthlyClose.col_exported_at")
    val colRowCount = StringKey("monthlyClose.col_row_count")
    val colFileHash = StringKey("monthlyClose.col_file_hash")
    val colSource = StringKey("monthlyClose.col_source")
    val sourceAuto = StringKey("monthlyClose.source_auto")
    val sourceManual = StringKey("monthlyClose.source_manual")
    val exportBuchungsstapelButton = StringKey("monthlyClose.export_buchungsstapel_button")
    val downloadButton = StringKey("monthlyClose.download_button")
    val exportInProgress = StringKey("monthlyClose.export_in_progress")

    // Catchup confirm dialog
    val catchupDialogTitle = StringKey("monthlyClose.catchup_dialog_title")
    val catchupDialogBodyPrefix = StringKey("monthlyClose.catchup_dialog_body_prefix")
    val catchupPeriodsWeeks = StringKey("monthlyClose.catchup_periods_weeks")
    val catchupPeriodsMonths = StringKey("monthlyClose.catchup_periods_months")
    val catchupConfirm = StringKey("monthlyClose.catchup_confirm")
    val catchupNothingToDo = StringKey("monthlyClose.catchup_nothing_to_do")

    // Ad-hoc export dialog
    val adHocDialogTitle = StringKey("monthlyClose.adhoc_dialog_title")
    val adHocDialogBody = StringKey("monthlyClose.adhoc_dialog_body")
    val adHocStartDate = StringKey("monthlyClose.adhoc_start_date")
    val adHocEndDate = StringKey("monthlyClose.adhoc_end_date")
    val adHocConfirm = StringKey("monthlyClose.adhoc_confirm")
    val adHocExcludeKassenbuch = StringKey("monthlyClose.adhoc_exclude_kassenbuch")
    val adHocExcludeKassenbuchHint = StringKey("monthlyClose.adhoc_exclude_kassenbuch_hint")
}
