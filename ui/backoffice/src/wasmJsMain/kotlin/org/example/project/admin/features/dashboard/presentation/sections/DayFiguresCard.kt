package org.example.project.admin.features.dashboard.presentation.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.dashboard.presentation.components.DashboardSectionHeader
import org.example.project.admin.features.dashboard.presentation.components.DashboardTile
import org.example.project.admin.features.dashboard.presentation.components.DashboardTileGrid
import org.example.project.admin.features.dashboard.presentation.dayFiguresNoRevenueKey
import org.example.project.admin.features.dashboard.presentation.dayFiguresTitleKey
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.formatMoneyForDisplay
import org.example.project.ui.theme.LocalPosLayout
import org.koin.compose.viewmodel.koinViewModel

/**
 * The only section that does not hide itself when it has nothing: "the till has taken nothing yet"
 * is an answer a manager opens the dashboard to get, and a missing card does not give it. It
 * collapses to one line instead, and keeps the way into the Tagesbericht.
 *
 * @param onOpenDailyReport `null` hides the link (the principal lacks `REPORT_ALL_READ`).
 * @param showsShopFigures false when the figures are the caller's own (server-scoped), which retitles the card.
 * @param refreshTick incremented by the screen's refresh button; the card reloads on every change.
 */
@Composable
fun DayFiguresCard(
    refreshTick: Int,
    onOpenDailyReport: (() -> Unit)?,
    showsShopFigures: Boolean,
    modifier: Modifier = Modifier,
    viewModel: DayFiguresViewModel = koinViewModel(),
) {
    LaunchedEffect(refreshTick) { viewModel.refresh() }

    val state = viewModel.state
    val title = str(dayFiguresTitleKey(showsShopFigures))

    val settled = rememberSectionSettled(state.isLoading, state.failed)
    if (state.isLoading && !settled) {
        SectionLoadingPlaceholder(modifier)
        return
    }
    if (state.failed) {
        SectionUnavailable(title = title, modifier = modifier)
        return
    }
    val overview = state.overview ?: return

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LocalPosLayout.current.gridGap)) {
        DashboardSectionHeader(
            title = title,
            linkText = str(AdminStringKeys.DashboardToDailyReport),
            onLinkClick = onOpenDailyReport,
        )
        if (!state.hasRevenue) {
            Text(
                text = str(dayFiguresNoRevenueKey(showsShopFigures)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val revenueLabel = str(AdminStringKeys.DashboardKpiRevenue)
            val billsLabel = str(AdminStringKeys.DashboardKpiBills)
            val openAmountLabel = str(AdminStringKeys.DashboardKpiOpenAmount)
            val tipsLabel = str(AdminStringKeys.DashboardKpiTips)
            val revenue = formatMoneyForDisplay(overview.totalRevenue, overview.currency)
            val openAmount = formatMoneyForDisplay(overview.openItemAmount, overview.currency)
            val tips = formatMoneyForDisplay(overview.totalTips, overview.currency)
            DashboardTileGrid(
                items =
                    listOf(
                        { m -> DashboardTile(label = revenueLabel, value = revenue, modifier = m) },
                        { m -> DashboardTile(label = billsLabel, value = overview.totalBillCount.toString(), modifier = m) },
                        { m -> DashboardTile(label = openAmountLabel, value = openAmount, modifier = m) },
                        { m -> DashboardTile(label = tipsLabel, value = tips, modifier = m) },
                    ),
            )
        }
    }
}
