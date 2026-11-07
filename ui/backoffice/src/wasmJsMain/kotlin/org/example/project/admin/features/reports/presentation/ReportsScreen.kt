package org.example.project.admin.features.reports.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.admin.core.presentation.components.AdminPageTitle
import org.example.project.admin.core.presentation.format.asDisplayDay
import org.example.project.admin.core.presentation.navigation.FilterPanelKeys
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.reports.presentation.components.CatalogSection
import org.example.project.admin.features.reports.presentation.components.FinancialSection
import org.example.project.admin.features.reports.presentation.components.MonthlyCloseBlock
import org.example.project.admin.features.reports.presentation.components.PromotionsSection
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.averageMoneyPerCount
import org.example.project.features.reports.presentation.components.dailyreport.KpiCard
import org.example.project.money.Decimal
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosKpiGrid
import org.example.project.ui.components.PosKpiSummary
import org.example.project.ui.components.PosLoadingOverlay
import org.example.project.ui.components.chart.HourlyRevenueChart
import org.example.project.ui.components.chart.WeekdayPatternChart
import org.example.project.ui.components.filter.rememberPosFilterPanelState
import org.example.project.ui.components.formatMoneyForDisplay
import org.example.project.ui.components.kpiSummaryText
import org.example.project.ui.layout.PaneSection
import org.example.project.ui.layout.PaneSelectorStyle
import org.example.project.ui.layout.PosPaneHost
import org.example.project.ui.theme.LocalPosLayout
import org.example.project.ui.theme.PosSpacing
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The Berichte (reports) back-office screen: the period the owner is looking at, the takings for it
 * with a like-for-like delta against the previous equal span, and the trade pattern (by hour and by
 * weekday). Data is loaded on entry — not in the VM's init — because the whole app shares one
 * ViewModelStoreOwner and the VM survives logout, so a LaunchedEffect re-fetches for each session.
 *
 * Every amount is formatted through the money kernel with the currency the server put on the
 * response DTO (never a client constant), and the average bill is *divided* through [Decimal], never
 * derived from a Double.
 */
// Explicit store key, kept as belt and braces. The real fix for the Analysen crash (T9-3) was the
// class name: this VM used to be called ReportsViewModel like the till's shared-ui one, and Koin
// indexes definitions by the SIMPLE class name on wasm, so one silently replaced the other in the
// module and this screen was handed the till's VM. AdminModuleDefinitionCollisionTest guards it.
@OptIn(ExperimentalTime::class)
@Composable
fun ReportsScreen(viewModel: AnalyticsViewModel = koinViewModel(key = "adminReportsViewModel")) {
    val state = viewModel.state
    LaunchedEffect(Unit) { viewModel.load(state.granularity, state.anchor) }

    PosErrorWatcher(
        error = state.error,
        title = str(AdminStringKeys.ReportsTitle),
        onClear = viewModel::clearError,
        retryAction = { viewModel.load(state.granularity, state.anchor) },
    )

    val today =
        Clock.System
            .now()
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date
    val screenPadding = LocalPosLayout.current.screenPadding
    // Above the layout branches below (compact vs wide bar), so a resize keeps an open panel and its draft.
    val filterPanel = rememberPosFilterPanelState(AnalyticsPanelFilter.of(state), FilterPanelKeys.ANALYTICS)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val barWidth = maxWidth - screenPadding * 2
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = PosSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(PosSpacing.lg),
        ) {
            AdminPageTitle(str(AdminStringKeys.ReportsTitle), Modifier.padding(horizontal = screenPadding))

            AnalyticsFilterBar(state, viewModel, today, barWidth, filterPanel)

            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = screenPadding),
                verticalArrangement = Arrangement.spacedBy(PosSpacing.xl),
            ) {
                // Retained across a reload (load() keeps the previous overview), so a period switch keeps
                // the old figures visible under the loading overlay instead of blanking the screen.
                state.overview?.let { overview -> AnalyticsKpis(overview) }

                // Phone: the five block chips wrapped to two lines; a one-line dropdown keeps the
                // first block (Auslastung) inside the 340dp first-row budget. Wider: chips as before.
                PosPaneHost(
                    sections = reportSections(state),
                    selectorStyle = if (LocalPosLayout.current.isCompact) PaneSelectorStyle.Dropdown else PaneSelectorStyle.Chips,
                )
            }
        }

        AnalyticsFilterPanel(state, viewModel, today, filterPanel)

        if (state.isLoading) {
            PosLoadingOverlay(title = str(AdminStringKeys.ReportsTitle))
        }
    }
}

@Composable
private fun reportSections(state: ReportsState): List<PaneSection> {
    val revenueTitle = str(AdminStringKeys.ReportsSectionRevenue)
    val taxTitle = str(AdminStringKeys.ReportsSectionTaxPayments)
    val catalogTitle = str(AdminStringKeys.ReportsSectionCatalog)
    val promotionsTitle = str(AdminStringKeys.ReportsSectionPromotions)
    val closeTitle = str(AdminStringKeys.ReportsSectionMonthlyClose)
    // Always all five, in a fixed order: PosPaneHost remembers the first id on first composition, so a
    // section that appeared later (data arriving) would leave the selection on "close".
    return listOf(
        PaneSection("revenue", revenueTitle) { state.trends?.let { trends -> BusySection(trends) } },
        PaneSection("tax", taxTitle) { state.financial?.let { financial -> FinancialSection(financial) } },
        PaneSection("catalog", catalogTitle) {
            state.catalog?.let { catalog -> CatalogSection(catalog, compareEnabled = state.compareEnabled) }
        },
        PaneSection("promotions", promotionsTitle) {
            state.promotions?.let { promo -> PromotionsSection(promo, compareEnabled = state.compareEnabled) }
        },
        PaneSection("close", closeTitle) {
            MonthlyCloseBlock(granularity = state.granularity, anchor = state.anchor, compareEnabled = state.compareEnabled)
        },
    )
}

/** Whether any bucket in a chart series carries money — the same test the charts themselves apply. */
private fun hasAnyTrade(series: Map<*, String>): Boolean = series.values.any { amount -> (Decimal.parseOrNull(amount) ?: Decimal.ZERO) > Decimal.ZERO }

/**
 * Wide: the KPI cards. Phone: a one-line summary of the period figures ([analyticsKpiSummaryItems])
 * that expands to the very same cards — the comparison deltas, the tips and the provisional hint
 * appear only there, so the collapsed line never mixes period and comparison figures.
 */
@Composable
private fun AnalyticsKpis(overview: StatisticsDto.SalesOverviewResponseDto) {
    if (!LocalPosLayout.current.isCompact) {
        KpiRow(overview)
        return
    }
    val summary =
        kpiSummaryText(
            analyticsKpiSummaryItems(
                overview = overview,
                revenueLabel = str(AdminStringKeys.ReportsKpiRevenue),
                billsLabel = str(AdminStringKeys.ReportsKpiBills),
                averageLabel = str(AdminStringKeys.ReportsKpiAverageBill),
                openLabel = str(AdminStringKeys.ReportsKpiOpen),
            ),
        )
    PosKpiSummary(summary = summary) {
        Column(verticalArrangement = Arrangement.spacedBy(PosSpacing.md)) { KpiRow(overview) }
    }
}

@Composable
private fun KpiRow(overview: StatisticsDto.SalesOverviewResponseDto) {
    val currency = overview.currency
    val comparison = overview.comparison
    // The baseline window start, e.g. "vs. 2026-07-30". Null exactly when there is no baseline, which
    // is also when every deltaPercent below is null, so KpiCard renders no chip and no orphan label.
    val comparedTo: String? =
        comparison?.let {
            str(AdminStringKeys.ReportsComparedTo, it.previousStart.asDisplayDay(LocalAppLanguage.current))
        }
    val accent = MaterialTheme.colorScheme.primary

    val revenueLabel = str(AdminStringKeys.ReportsKpiRevenue)
    val billsLabel = str(AdminStringKeys.ReportsKpiBills)
    val averageLabel = str(AdminStringKeys.ReportsKpiAverageBill)
    val tipsLabel = str(AdminStringKeys.ReportsKpiTips)
    val openLabel = str(AdminStringKeys.ReportsKpiOpen)
    val tertiary = MaterialTheme.colorScheme.tertiary
    val items = mutableListOf<@Composable (Modifier) -> Unit>()
    items +=
        { m ->
            KpiCard(
                label = revenueLabel,
                value = formatMoneyForDisplay(overview.totalRevenue, currency),
                accentColor = accent,
                modifier = m,
                deltaPercent = comparison?.revenueDeltaPercent,
                deltaLabel = comparedTo,
            )
        }
    items +=
        { m ->
            KpiCard(
                label = billsLabel,
                // A count, not money — never routed through the money formatter.
                value = overview.totalBillCount.toString(),
                accentColor = accent,
                modifier = m,
                deltaPercent = comparison?.billCountDeltaPercent,
                deltaLabel = comparedTo,
            )
        }
    items +=
        { m ->
            KpiCard(
                label = averageLabel,
                value = averageMoneyPerCount(overview.totalRevenue, overview.totalBillCount, currency),
                accentColor = accent,
                modifier = m,
                deltaPercent = comparison?.averageBillDeltaPercent,
                deltaLabel = comparedTo,
            )
        }
    items +=
        { m ->
            KpiCard(
                label = tipsLabel,
                value = formatMoneyForDisplay(overview.totalTips, currency),
                accentColor = accent,
                modifier = m,
                deltaPercent = comparison?.tipsDeltaPercent,
                deltaLabel = comparedTo,
            )
        }
    // Only when there is something open: an extra card reading 0,00 on every settled period
    // would be noise, and the point of this one is that it is unusual.
    if (hasOpenAmount(overview)) {
        items +=
            { m ->
                KpiCard(
                    label = openLabel,
                    value = formatMoneyForDisplay(overview.openItemAmount, currency),
                    // Tertiary, not the shared accent: this figure is provisional and must not read as
                    // a peer of the fiscal ones beside it.
                    accentColor = tertiary,
                    modifier = m,
                )
            }
    }
    PosKpiGrid(items = items)
    if (hasOpenAmount(overview)) {
        Text(
            text = str(AdminStringKeys.ReportsOpenProvisionalHint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BusySection(trends: StatisticsDto.SalesTrendsResponseDto) {
    val dayLabels =
        listOf(
            str(CommonKeys.weekdayShortMon),
            str(CommonKeys.weekdayShortTue),
            str(CommonKeys.weekdayShortWed),
            str(CommonKeys.weekdayShortThu),
            str(CommonKeys.weekdayShortFri),
            str(CommonKeys.weekdayShortSat),
            str(CommonKeys.weekdayShortSun),
        )
    PosCard(modifier = Modifier.fillMaxWidth()) {
        body {
            Text(
                text = str(AdminStringKeys.ReportsBusyTitle),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            // Both charts return without drawing when there is nothing to plot, which left three
            // headings floating over blank space on a day with no trade. The section now says so,
            // in the same words the Katalog and Aktionen sections use.
            //
            // The test is "no amount above zero", not "the map is empty": the server sends a full
            // set of buckets with 0.00 in each, so an emptiness check on the map never fires — which
            // is exactly how the first attempt at this missed.
            if (!hasAnyTrade(trends.hourlyRevenue) && !hasAnyTrade(trends.weeklyPattern)) {
                Text(
                    text = str(AdminStringKeys.ReportsCatalogEmpty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = str(AdminStringKeys.ReportsHourlyTitle),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = str(AdminStringKeys.ReportsHourlyTradedOnly),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HourlyRevenueChart(hourlyRevenue = trends.hourlyRevenue, currency = trends.currency)
                Text(
                    text = str(AdminStringKeys.ReportsWeekdayTitle),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                WeekdayPatternChart(
                    weeklyPattern = trends.weeklyPattern,
                    currency = trends.currency,
                    dayLabels = dayLabels,
                )
            }
        }
    }
}
