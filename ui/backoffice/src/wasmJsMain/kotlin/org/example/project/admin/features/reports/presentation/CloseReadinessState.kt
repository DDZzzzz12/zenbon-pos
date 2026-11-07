package org.example.project.admin.features.reports.presentation

import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import org.example.project.core.presentation.error.UiError

/**
 * The month-close view over the period [ReportsState] is already showing: the KPI series, the
 * readiness checklist, and the Z-Bon / Kassenbuch roll-ups.
 *
 * Held apart from [ReportsState] because `GET /api/v1/monthly-close` is MANAGER-only while
 * `AdminNavItem.ANALYTICS` is ungated (`{ true }`). Folding it into `AnalyticsViewModel`'s
 * `coroutineScope { async … }` would let one 403 fail the whole Berichte screen for a non-manager,
 * so it loads on its own and fails on its own.
 *
 * [data] is deliberately retained across a reload so a period switch keeps the previous figures
 * under the loading indicator instead of blanking, matching [ReportsState].
 */
data class CloseReadinessState(
    val isLoading: Boolean = false,
    val error: UiError? = null,
    val data: MonthlyCloseDto.MonthlyCloseResponseDto? = null,
)
