/**
 * UI state definitions for Reports screens.
 *
 * Defines all possible states for the Reports feature including loading,
 * success, and error states with proper data structures.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.reports.presentation.state

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.state.DateRange
import org.example.project.ui.components.BillDetailTrail

data class ReportsUiState(
    val isLoading: Boolean = true,
    val isLoadingDetail: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isLoadingCanceledItems: Boolean = false,
    val error: UiError? = null,
    val timeRange: Pair<String, String>? = null,
    val selectedDatePreset: DateFilterPreset = DateFilterPreset.SINCE_LAST_ZBON,
    // KPI summary data
    val totalRevenue: String = "0,00 €",
    val billedRevenue: String = "0,00 €",
    val totalTips: String = "0,00 €",
    val canceledRevenue: String = "0,00 €",
    val refundRevenue: String = "0,00 €",
    val totalDiscounts: String = "0,00 €",
    val openItemAmount: String = "0,00 €",
    val revenueByPaymentMethod: List<PaymentDto.PaymentSummary> = emptyList(),
    /**
     * Receivable clearings of the period, formatted — inside [revenueByPaymentMethod] but not inside
     * [totalRevenue] (the turnover was booked when the invoice was issued). Null when there are none.
     */
    val receivableCleared: String? = null,
    val totalBillCount: Int = 0,
    /**
     * The currency the period's figures are in, straight from the statistics response.
     *
     * Kept in state so a host that has no shop-wide setting manager — the back office — can still
     * format money in the currency the numbers beside it are actually in.
     */
    val currency: String = "EUR",
    // Detail data (accumulated for infinite scroll)
    val onlyBills: List<BillDto.BillSummaryDto> = emptyList(),
    val tableCards: List<StatisticsDto.TableCardDto> = emptyList(),
    val employeeGroups: List<StatisticsDto.EmployeeGroupDto> = emptyList(),
    /** Per-employee tips of the period for the Trinkgeld sheet — loaded on demand, independent of the Mitarbeiter tab. */
    val tipEarners: List<StatisticsDto.EmployeeGroupDto> = emptyList(),
    val tipEarnersLoaded: Boolean = false,
    val isLoadingTipEarners: Boolean = false,
    /**
     * Total tips ÷ bill count of the period, formatted — computed from the raw total, not [totalTips].
     * Empty until a report has loaded: a default display string would claim a figure nobody computed.
     */
    val averageTipPerBill: String = "",
    // Infinite scroll flags
    val hasMoreBills: Boolean = true,
    val hasMoreTableCards: Boolean = true,
    val hasMoreEmployeeGroups: Boolean = true,
    // Table session drill-down state
    val expandedTableSessions: Map<Long, List<StatisticsDto.TableSessionSummaryDto>> = emptyMap(),
    val loadingTableSessions: Set<Long> = emptySet(),
    // Canceled items (loaded on-demand for bottom sheet)
    val canceledItems: List<StatisticsDto.CanceledItemDto> = emptyList(),
    // Payment records (loaded on-demand for payments bottom sheet)
    val paymentRecords: List<StatisticsDto.PaymentRecordDto> = emptyList(),
    val isLoadingPayments: Boolean = false,
    val showPaymentsBottomSheet: Boolean = false,
    // Sort option for transaction list
    val sortOption: StatisticsDto.StatisticsSortOption = StatisticsDto.StatisticsSortOption.RECENT,
    // Employee filter (managers only)
    val availableEmployees: List<EmployeeDto.EmployeeSummaryResponseDto> = emptyList(),
    val selectedEmployeeId: String? = null,
    val isLoadingEmployees: Boolean = false,
    val canReportShopWide: Boolean = false,
    // Training mode (manager+ only)
    val trainingMode: Boolean = false,
    // Payment type filter
    val selectedPaymentTypes: Set<String> = emptySet(),
    val availablePaymentTypes: List<String> = emptyList(),
    // UI dialog/panel state
    val selectedTabIndex: Int = 0,
    /**
     * A bill row was tapped and its full [BillDto.BillResponseDto] is still being fetched.
     *
     * The list only carries a [BillDto.BillSummaryDto], so opening a receipt always costs a
     * round trip. Without this flag the tap looks like a no-op for the length of that call.
     */
    val isLoadingBill: Boolean = false,
    val showBillPanel: Boolean = false,
    val selectedBill: BillDto.BillResponseDto? = null,
    /** The bills left behind by following related-bill links in the open panel ("Zurück zu RE-…"). */
    val billTrail: BillDetailTrail = BillDetailTrail(),
    /**
     * Bumped when the first page reloaded after a bill change (Storno, Zahlartwechsel, …) has
     * landed: the list scrolls to its top once, so the new Belege — prepended above the row the
     * list was anchored on — come into view. Pull-to-refresh and paging never bump it.
     */
    val scrollToTopRequest: Int = 0,
    val showZBonConfirmDialog: Boolean = false,
    val showBillSearchSheet: Boolean = false,
    val showCustomDateRangeDialog: Boolean = false,
)

/**
 * State for Z-Bon history panel.
 *
 * Encapsulates all state related to the Z-Bon history side panel
 * including visibility, data, loading, and filters.
 */
data class ZBonState(
    /** List of Z-Bon summaries */
    val zbonHistory: List<ZBonDto.ZBonSummeryDto> = emptyList(),
    /** Currently selected date range filter */
    val selectedDateRange: DateRange = DateRange.THIS_WEEK,
    /** Custom date range (used when selectedDateRange is CUSTOM) */
    val customDateRange: Pair<String, String>? = null,
    /** Whether Z-Bon history is currently loading */
    val isLoading: Boolean = false,
    val selectedZBon: ZBonDto.ZBonResponseDto? = null,
    /**
     * The Z-Bon this operator has just closed, shown in a dialog because they may not open the
     * history (no `ZBON_MANAGE`), where [selectedZBon] would otherwise be displayed. Null when none
     * is pending or the closing opened in the history tab instead.
     */
    val createdZBon: ZBonDto.ZBonResponseDto? = null,
    /** PDF bytes for viewing in DocumentViewerPanel */
    val pdfBytes: ByteArray? = null,
    /** File name for the PDF viewer */
    val pdfFileName: String? = null,
    /** Error for Z-Bon history loading failure (null if no error) */
    val error: UiError? = null,
    /** Success message */
    val successMessage: String? = null,
    /**
     * The fiscal book's balance as a canonical decimal string, prefilled into the drawer-count
     * field of the closing dialog. Null when it could not be loaded — the count is optional, so a
     * failed lookup leaves the field empty rather than blocking the closing.
     */
    val expectedCashBalance: String? = null,
    /** The open "Z-Bon erstellen" dialog — drawer count and in-closing tip payouts (F58); null when closed. */
    val closingDraft: ZBonClosingDraft? = null,
    /** The tip payout being confirmed on the shown Z-Bon, or null when no payout dialog is open. */
    val tipPayoutDraft: TipPayoutDraft? = null,
    /** The storno of a booked tip payout being confirmed, or null when no storno dialog is open. */
    val tipReversalDraft: TipReversalDraft? = null,
)

/**
 * Reports view mode for different user roles.
 */
enum class ReportsViewMode {
    /** Cashier view - personal statistics only */
    CASHIER,

    /** Manager+ view - full access (to be implemented) */
    MANAGER_PLUS,
}
