/**
 * Container object for all statistics-related DTOs.
 *
 * Organizes DTOs by functionality: sales, products, employees, tables, and financial
 * to maintain clear separation of concerns and consistent API structure.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.dto.statistics

import kotlinx.serialization.Serializable
import org.example.project.core.domain.dto.ApiResponseDto
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.table.TableSessionDto
import org.example.project.core.domain.enums.bill.PaymentStatus

/**
 * Container object for all POS statistics DTOs.
 *
 * Provides comprehensive business intelligence data structures for
 * sales analysis, product performance, employee metrics, and financial reporting.
 */
object StatisticsDto {
    // =========================== SALES STATISTICS ===========================

    /**
     * Response DTO for sales overview statistics.
     *
     * Provides key performance indicators for business monitoring
     * and executive dashboard reporting.
     */
    @Serializable
    data class SalesOverviewResponseDto(
        /** Date period for the statistics (ISO format) */
        val period: String,
        /** Total revenue for the period */
        val totalRevenue: String,
        val billedRevenue: String,
        val canceledRevenue: String,
        /** Total refund revenue from storno bills (Belegstorno) */
        val refundRevenue: String = "0.00",
        val openItemAmount: String,
        /**
         * "Rabatte" for the period: manual line discounts plus promotion deductions (bill-wide and
         * item promotions) of the active bills — stornoed originals and their Stornos excluded, a
         * Zahlartwechsel counted once through its Neubuchung. The same figure the Z-Bon reports as
         * `totalDiscounts`.
         */
        val totalDiscounts: String = "0.00",
        /** Total tips collected */
        val totalTips: String,
        /** Revenue breakdown by payment method */
        val revenueByPaymentMethod: List<PaymentDto.PaymentSummary> = emptyList(),
        /**
         * Receivable clearings in the period ("Forderungsausgleich"): money collected at the till for
         * an outgoing invoice issued earlier. It is in [revenueByPaymentMethod] — the money came in —
         * but not in [totalRevenue]/[billedRevenue], because the turnover was booked when the invoice
         * was issued. The Z-Bon's `totalReceivableCleared`.
         */
        val receivableCleared: String = "0.00",
        /** Total number of bills in the date range */
        val totalBillCount: Int = 0,
        /** Total number of sessions in the date range */
        val totalSessionCount: Int = 0,
        /**
         * ISO 4217 code the monetary fields of this response are denominated in.
         *
         * Supplied by the server from shop config (`ConfigKey.DEFAULT_CURRENCY`), resolved once
         * per request by the service, so clients never guess it from a constant. One field per
         * response root — nested objects inherit it. The default only keeps non-server
         * construction sites compiling; every server path sets it explicitly.
         */
        val currency: String = "EUR",
        /** Present only when the request asked for `compare=previous` and a baseline exists. */
        val comparison: PeriodComparisonDto? = null,
    )

    /**
     * A like-for-like baseline for a [SalesOverviewResponseDto], covering an equal elapsed span.
     *
     * Deltas are computed server-side. Precedent: [SalesTrendsResponseDto.growthRate] is already a
     * server-computed string. It also avoids a module problem — `shared` has no `:platform-money` dependency
     * (pos-server consumes it and uses BigDecimal directly), so client-side arithmetic would have to
     * live in `shared-ui`, splitting one rule across two layers.
     */
    @Serializable
    data class PeriodComparisonDto(
        /** Baseline window, echoed so the UI can label the delta ("vs 1.–10. Mai"). */
        val previousStart: String,
        val previousEnd: String,
        val previousTotalRevenue: String,
        val previousBilledRevenue: String,
        val previousTotalTips: String,
        val previousTotalBillCount: Int,
        val previousTotalSessionCount: Int,
        /** Derived as revenue / billCount; "0.00" when the baseline had no bills. */
        val previousAverageBill: String,
        /**
         * Signed percentage change, 2 dp, HALF_UP — e.g. "12.40", "-3.10".
         * Null when the baseline figure is zero: a percentage against zero is not meaningful and
         * must never render as +100 %.
         */
        val revenueDeltaPercent: String? = null,
        val billCountDeltaPercent: String? = null,
        val averageBillDeltaPercent: String? = null,
        val tipsDeltaPercent: String? = null,
    )

    /**
     * Response DTO for sales trends analysis.
     *
     * Provides time-series data for trend analysis and forecasting.
     */
    @Serializable
    data class SalesTrendsResponseDto(
        /** Start date of the analysis period */
        val startDate: String,
        /** End date of the analysis period */
        val endDate: String,
        /** Daily revenue data (ISO date -> revenue) */
        val dailyRevenue: Map<String, String>,
        /** Daily transaction counts */
        val dailyTransactionCounts: Map<String, Int>,
        /** Peak hours analysis (hour -> revenue) */
        val hourlyRevenue: Map<Int, String>,
        /** Growth trend indicators */
        val growthRate: String,
        /** Best performing days */
        val bestPerformingDays: List<String>,
        /** Revenue distribution by day of week */
        val weeklyPattern: Map<String, String>,
        /**
         * ISO currency code for every amount in this response.
         *
         * Carried on the DTO so the client formats from the response rather than a client-side
         * constant — see `platform/money/README.md`. Defaulted for wire compatibility with any payload
         * serialized before this field existed.
         */
        val currency: String = "EUR",
    )

    // =========================== PRODUCT STATISTICS ===========================

    /**
     * Response DTO for best-selling products analysis.
     *
     * Identifies top-performing products for inventory and menu optimization.
     */
    @Serializable
    data class BestSellersResponseDto(
        /** Analysis period */
        val period: String,
        /** Top products by quantity sold */
        val topProductsByQuantity: List<ProductPerformanceDto>,
        /** Top products by revenue */
        val topProductsByRevenue: List<ProductPerformanceDto>,
        /** Products with highest growth */
        val fastestGrowingProducts: List<ProductPerformanceDto>,
        /** Total unique products sold */
        val uniqueProductsSold: Int,
    )

    /**
     * Individual product performance metrics.
     */
    @Serializable
    data class ProductPerformanceDto(
        /** Product ID */
        val productId: Long,
        /** Product name */
        val productName: String,
        /** Product category */
        val categoryName: String,
        /** Quantity sold */
        val quantitySold: Long,
        /** Revenue generated */
        val revenue: String,
        /** Percentage of total sales; null — not computed (no screen or report reads it). */
        val salesPercentage: String? = null,
        /** Growth rate compared to previous period; null when compare is off or no baseline exists. */
        val growthRate: String? = null,
    )

    /**
     * Response DTO for product category performance.
     *
     * Analyzes performance by product categories for strategic decisions.
     */
    @Serializable
    data class CategoryPerformanceResponseDto(
        /** Analysis period */
        val period: String,
        /** Category performance breakdown */
        val categoryMetrics: List<CategoryMetricsDto>,
        /** Most profitable category */
        val mostProfitableCategory: String,
        /** Fastest growing category */
        val fastestGrowingCategory: String,
        /** Category contribution to total revenue */
        val categoryRevenueDistribution: Map<String, String>,
        /**
         * Bill-wide promotions (Aktionen on the whole bill), a positive money string. They cannot be
         * attributed to one category, so the categories are net of line discounts and item
         * promotions only and this is subtracted once, as its own reconciliation row.
         */
        val billWidePromotions: String = "0.00",
        /** Owner tips (Inhabertrinkgeld) — taxable turnover that belongs to no category. */
        val ownerTips: String = "0.00",
        /**
         * Σ category revenue − [billWidePromotions] + [ownerTips]: the Umsatz the category column
         * reconciles to — the overview's Umsatz for the same active bills.
         */
        val reconciledRevenue: String = "0.00",
    )

    /**
     * Individual category performance metrics.
     */
    @Serializable
    data class CategoryMetricsDto(
        /** Category ID */
        val categoryId: Int,
        /** Category name */
        val categoryName: String,
        /** Number of products in category */
        val productCount: Int,
        /** Total quantity sold */
        val totalQuantitySold: Long,
        /**
         * Revenue charged for the category's lines: Σ finalPrice (incl. extras, after manual line
         * discounts) less the item promotions granted on those lines. Bill-wide promotions and owner
         * tips are carried on [CategoryPerformanceResponseDto] instead.
         */
        val totalRevenue: String,
        /** Average product price in category */
        val averagePrice: String,
        /** Growth rate; null when compare is off or no baseline exists. */
        val growthRate: String? = null,
    )

    /** One extras option's uptake in the window (from the snapshotted item_extras rows). */
    @Serializable
    data class ExtraOptionStatDto(
        val optionName: String,
        val groupName: String? = null,
        val timesChosen: Long,
        /** Added revenue = Σ(priceDeltaSnapshot × quantity), money string. */
        val addedRevenue: String,
    )

    /** The Katalog section payload: products + categories + extras for one window. */
    @Serializable
    data class CatalogBreakdownResponseDto(
        val period: String,
        val currency: String,
        val bestSellers: BestSellersResponseDto,
        val categories: CategoryPerformanceResponseDto,
        val extras: List<ExtraOptionStatDto>,
    )

    /** One promotion's uptake in the window (from the bill_promotion rows). */
    @Serializable
    data class PromotionStatDto(
        val promotionId: Long,
        val name: String,
        /** DiscountType enum name (FIXED_AMOUNT | PERCENTAGE | FREE_ITEM) — the client localizes the label. */
        val discountType: String,
        /** Number of bill_promotion rows for this promotion in the window. */
        val timesApplied: Long,
        /** Σ deductedAmount over the window, money string. */
        val totalDeducted: String,
        /** Prior-period delta percent for timesApplied; null when compare is off or no baseline exists. */
        val timesAppliedDeltaPercent: String? = null,
        /** Prior-period delta percent for totalDeducted; null when compare is off or no baseline exists. */
        val totalDeductedDeltaPercent: String? = null,
    )

    /** The Aktionen section payload: per-promotion deducted amounts for one window. */
    @Serializable
    data class PromotionBreakdownResponseDto(
        val period: String,
        /** Σ deductedAmount across all promotions in the window, money string. */
        val totalDeducted: String,
        val currency: String,
        val promotions: List<PromotionStatDto>,
    )

    // =========================== FINANCIAL STATISTICS ===========================

    /**
     * Response DTO for financial overview.
     *
     * Provides comprehensive financial metrics for accounting and compliance.
     */
    @Serializable
    data class FinancialOverviewResponseDto(
        /** Analysis period */
        val period: String,
        /** ISO 4217 currency code the amounts are denominated in */
        val currency: String,
        /** Total gross revenue */
        val grossRevenue: String,
        /** Net revenue after refunds */
        val netRevenue: String,
        /** Total refunds issued */
        val totalRefunds: String,
        /** Total tips collected */
        val totalTips: String = "0.00",
        /** VAT breakdown */
        val vatBreakdown: Map<String, VatSummaryDto>,
        /** Payment method distribution */
        val paymentMethodBreakdown: Map<String, PaymentMethodSummaryDto>,
        /**
         * Receivable clearings in the period ("Forderungsausgleich"): inside [paymentMethodBreakdown],
         * not inside [grossRevenue] — see `SalesOverviewResponseDto.receivableCleared`.
         */
        val receivableCleared: String = "0.00",
        /** Cash flow indicators */
        val cashFlow: CashFlowDto,
        /** Daily financial summary */
        val dailyFinancialSummary: Map<String, DailyFinancialDto>,
    )

    /**
     * Cash flow indicators.
     */
    @Serializable
    data class CashFlowDto(
        /** Opening cash balance */
        val openingBalance: String,
        /** Cash sales */
        val cashSales: String,
        val expenses: String,
        /** Cash refunds */
        val cashRefunds: String,
        /** Net cash flow */
        val netCashFlow: String,
        /** Closing cash balance */
        val closingBalance: String,
    )

    /**
     * Daily financial summary.
     */
    @Serializable
    data class DailyFinancialDto(
        /** Date (ISO format) */
        val date: String,
        /** Gross revenue */
        val grossRevenue: String,
        /** Net revenue */
        val netRevenue: String,
        val vatAmount: String,
        /** Transaction count */
        val transactionCount: Int,
        /** Average transaction value */
        val averageTransactionValue: String,
    )

    // =========================== PAGINATED STATISTICS ===========================

    /**
     * Sort options for paginated statistics queries.
     *
     * Shared between client and server to ensure consistent sorting behavior.
     */
    @Serializable
    enum class StatisticsSortOption {
        /** Most recent first (by end time descending) */
        RECENT,

        /** Oldest first (by end time ascending) */
        OLDEST,

        /** Highest amount first (by paid amount descending) */
        HIGHEST,

        /** Lowest amount first (by paid amount ascending) */
        LOWEST,
    }

    /**
     * Paginated bills response for the BILLS view mode.
     *
     * Contains a page of bill summaries plus cross-page aggregation totals
     * for the aggregation summary card.
     */
    @Serializable
    data class PaginatedBillsResponseDto(
        /** Bills for the current page */
        val bills: List<BillDto.BillSummaryDto>,
        /** Total paid amount across ALL pages (for aggregation card) */
        val totalAmount: String,
        /** Total tips across ALL pages (for aggregation card) */
        val totalTips: String,
        /** Pagination metadata */
        val pagination: ApiResponseDto.PaginationInfo,
    )

    /**
     * Aggregated card for a single table in the TABLES view mode.
     *
     * Shows per-table statistics including location type info and session count.
     */
    @Serializable
    data class TableCardDto(
        val tableId: Long,
        val tableNumber: String,
        val locationTypeName: String,
        val locationTypeEN: String? = null,
        val locationTypeCH: String? = null,
        val locationTypeSortOrder: Int,
        val isVirtual: Boolean,
        val totalRevenue: String,
        val totalTips: String,
        val totalBillCount: Int,
        val sessionCount: Int,
    )

    /**
     * Summary of a single table session with its bills.
     *
     * Used as drill-down from a TableCardDto.
     */
    @Serializable
    data class TableSessionSummaryDto(
        val sessionId: Long,
        val startTime: String,
        val endTime: String? = null,
        val revenue: String,
        val tips: String,
        val billCount: Int,
        val bills: List<BillDto.BillSummaryDto>,
    )

    /**
     * Paginated table cards response for the TABLES view mode.
     *
     * Contains a page of table cards with per-table aggregated data.
     */
    @Serializable
    data class PaginatedTableCardsResponseDto(
        /** Table cards for the current page */
        val tableCards: List<TableCardDto>,
        /** Pagination metadata */
        val pagination: ApiResponseDto.PaginationInfo,
    )

    /**
     * Response containing session summaries for a specific table.
     */
    @Serializable
    data class TableSessionsResponseDto(
        val sessions: List<TableSessionSummaryDto>,
    )

    /**
     * Single employee group with aggregated metrics and embedded bills.
     *
     * Used in the EMPLOYEES view mode to show per-employee statistics.
     */
    @Serializable
    data class EmployeeGroupDto(
        /** Employee number identifier */
        val employeeNumber: String,
        /**
         * The operator's username (the shared device account as "Terminal (ohne Anmeldung)"). Kept on
         * the wire for clients that predate [displayName]; show [shownName] instead.
         */
        val employeeName: String,
        /** Total revenue for this employee */
        val totalRevenue: String,
        /** Total tips for this employee */
        val totalTips: String,
        /** Number of bills for this employee */
        val billCount: Int,
        /** Bills belonging to this employee group */
        val bills: List<BillDto.BillSummaryDto>,
        /**
         * The HR first + last name, but only for a caller holding `EMPLOYEE_READ` (personnel data);
         * for any other caller, and for an employee without a personnel record, it equals
         * [employeeName]. The server always fills it; the default only lets a response from an older
         * server (which does not send it) still decode.
         */
        val displayName: String = "",
    ) {
        /** The name to show: [displayName], or [employeeName] from a server that does not send one. */
        fun shownName(): String = displayName.ifBlank { employeeName }
    }

    /**
     * Paginated employee groups response for the EMPLOYEES view mode.
     *
     * Contains a page of employee groups with per-employee aggregated data.
     */
    @Serializable
    data class PaginatedEmployeeGroupsResponseDto(
        /** Employee groups for the current page */
        val employeeGroups: List<EmployeeGroupDto>,
        /** Pagination metadata */
        val pagination: ApiResponseDto.PaginationInfo,
    )

    /**
     * Paginated sessions response for the SESSIONS view mode.
     *
     * Contains a page of session summaries with embedded bills.
     */
    @Serializable
    data class PaginatedSessionsResponseDto(
        /** Sessions for the current page */
        val sessions: List<TableSessionDto.SessionSummaryDto>,
        /** Pagination metadata */
        val pagination: ApiResponseDto.PaginationInfo,
    )

    // =========================== PAYMENT RECORDS ===========================

    /**
     * Individual payment record from the payment table.
     *
     * Represents a single payment transaction with payment type, status,
     * amount, bill reference, and timing information.
     */
    @Serializable
    data class PaymentRecordDto(
        /** Payment ID */
        val id: Long,
        /** Bill ID this payment belongs to */
        val billId: Long,
        /** Bill number for display */
        val billNumber: String,
        /** Payment type name (localized DE) */
        val paymentTypeDE: String,
        /** Payment type name (English) */
        val paymentTypeEN: String? = null,
        /** Payment type name (Chinese) */
        val paymentTypeCH: String? = null,
        /** Payment amount */
        val amount: String,
        /** Currency code (e.g. EUR) */
        val currency: String,
        /** Current payment status (COMPLETED, REFUNDED) */
        val paymentStatus: PaymentStatus,
        /** When the payment was processed (ISO format) */
        val paymentTime: String,
        /** External transaction reference (e.g. card terminal) */
        val transactionId: String? = null,
        /** When the payment was refunded (ISO format, null if not refunded) */
        val refundTime: String? = null,
    )

    /**
     * Paginated payment records response.
     *
     * Contains a page of individual payment records for the selected time range.
     */
    @Serializable
    data class PaginatedPaymentsResponseDto(
        /** Payment records for the current page */
        val payments: List<PaymentRecordDto>,
        /** Pagination metadata */
        val pagination: ApiResponseDto.PaginationInfo,
    )

    // =========================== REQUEST DTOS ===========================

    /**
     * Request DTO for statistics queries with date range.
     */
    @Serializable
    data class StatisticsRequestDto(
        /** Start date (ISO format) */
        val startDate: String,
        /** End date (ISO format) */
        val endDate: String,
        /** Optional filters */
        val filters: StatisticsFiltersDto? = null,
    )

    /**
     * Filters for statistics queries.
     */
    @Serializable
    data class StatisticsFiltersDto(
        /** Filter by specific employee IDs */
        val employeeIds: List<String>? = null,
        /** Filter by product categories */
        val categoryIds: List<Long>? = null,
        /** Filter by payment methods */
        val paymentMethods: List<String>? = null,
        /** Include only specific days of week (1-7, Monday=1) */
        val daysOfWeek: List<Int>? = null,
        /** Hour range filter (0-23) */
        val hourRange: Pair<Int, Int>? = null,
    )

    /**
     * Individual canceled item record.
     */
    @Serializable
    data class CanceledItemDto(
        val itemName: String,
        val unitPrice: String,
        val quantity: Int,
        val totalAmount: String,
        val billNumber: String,
        /** The operator's username (see [EmployeeGroupDto.employeeName]); show [shownName] instead. */
        val employeeName: String,
        val time: String,
        val reason: String,
        /** As [EmployeeGroupDto.displayName]: the HR name for a caller holding EMPLOYEE_READ, else the username. */
        val displayName: String = "",
    ) {
        /** The name to show: [displayName], or [employeeName] from a server that does not send one. */
        fun shownName(): String = displayName.ifBlank { employeeName }
    }

    /**
     * Response DTO for canceled items query.
     */
    @Serializable
    data class CanceledItemsResponseDto(
        val canceledItems: List<CanceledItemDto>,
        val totalAmount: String,
        val totalCount: Int,
    )
}
