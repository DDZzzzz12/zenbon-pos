package org.example.project.admin.features.voucher.presentation

import kotlinx.datetime.LocalDate
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.presentation.error.UiError
import org.example.project.features.reports.presentation.ReportsGranularity

/** The four views of the Gutscheine destination. */
enum class VoucherTab { ISSUED, ACTIVE, REDEEMED, SETTINGS }

/**
 * UI state for the Gutscheine screen. The four result lists are held separately so switching tabs
 * never refetches; only [issued] and [redeemed] are invalidated by a period or employee change.
 */
data class VoucherUiState(
    val tab: VoucherTab = VoucherTab.ISSUED,
    val granularity: ReportsGranularity = ReportsGranularity.MONTH,
    /** The date identifying the period shown; the ViewModel seeds it with today. */
    val anchor: LocalDate,
    /** Employee UUID filter, null = all employees. */
    val employeeId: String? = null,
    val searchTerm: String = "",
    val searchCommitted: Boolean = false,
    val issued: List<VoucherDto.VoucherResponseDto> = emptyList(),
    val redeemed: List<VoucherDto.VoucherRedemptionSummaryDto> = emptyList(),
    val active: List<VoucherDto.VoucherResponseDto> = emptyList(),
    val searchResults: List<VoucherDto.VoucherResponseDto> = emptyList(),
    val activeLoaded: Boolean = false,
    val activeHasMore: Boolean = true,
    val searchHasMore: Boolean = true,
    val statistics: VoucherDto.VoucherStatisticsDto? = null,
    val currencyCode: String = "EUR",
    val selected: VoucherDto.VoucherResponseDto? = null,
    val history: VoucherDto.VoucherRedemptionHistoryDto? = null,
    val isHistoryLoading: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: UiError? = null,
) {
    /** Search replaces the tab list and spans every period. */
    val isSearchActive: Boolean get() = searchCommitted && searchTerm.isNotBlank()

    /**
     * `/vouchers/active` and `/vouchers/search` accept only limit/offset — no date range and no
     * employeeId — so the period stepper and employee picker apply to the two date-range tabs only.
     */
    val filtersApply: Boolean
        get() = !isSearchActive && (tab == VoucherTab.ISSUED || tab == VoucherTab.REDEEMED)
}
