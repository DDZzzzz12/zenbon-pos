package org.example.project.ui.screens.reports.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import org.example.project.core.presentation.strings.keys.StatisticsDashboardKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.isCompact
import org.example.project.features.reports.presentation.components.dailyreport.KpiCard
import org.example.project.features.reports.presentation.components.dailyreport.KpiCardType
import org.example.project.ui.components.PosHorizontalScroller
import org.example.project.ui.components.PosKpiGrid

/** Fixed card width for the compact (phone) horizontally-scrolling KPI row. */
private val CompactKpiCardWidth = 150.dp

/** Minimum card width in the wrapping (non-compact) grid, so a money amount never ellipsizes. */
private val RegularKpiCardMinWidth = 160.dp

@Composable
fun KpiCardGrid(
    totalRevenue: String,
    totalTips: String,
    totalBillCount: Int,
    canceledRevenue: String,
    openAmount: String,
    windowSize: WindowSizeClass,
    onCardClick: ((KpiCardType) -> Unit)? = null,
    modifier: Modifier = Modifier,
    compact: Boolean = windowSize.isCompact(),
) {
    // Open amount (value of still-open items in open sessions) only matters when there is one —
    // show it as the leading card only when non-zero. Format-agnostic check: any 1-9 digit means
    // a non-zero amount, regardless of "0.00" vs the "0,00 €" placeholder formatting.
    val hasOpenAmount = openAmount.any { it in '1'..'9' }

    if (compact) {
        // Single horizontally-scrolling row of fixed-width cards. A bounded width is required
        // because KpiCard/PosCard force fillMaxWidth internally, which collapses inside a
        // horizontalScroll (unbounded width). This keeps the KPIs pinned but only ~1 row tall.
        // IntrinsicSize.Min + fillMaxHeight on each card: only the cards with a bottom sheet draw
        // the "tap for details" line, so the open-amount card is one line shorter and the row came
        // out ragged. Measuring the row to its tallest child and stretching the rest evens them up
        // without padding a hint onto a card that has nothing to open.
        PosHorizontalScroller(
            modifier =
                modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (hasOpenAmount) {
                KpiCard(
                    label = str(StatisticsDashboardKeys.openAmount),
                    value = openAmount,
                    accentColor = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.width(CompactKpiCardWidth).fillMaxHeight(),
                )
            }
            KpiCard(
                label = str(StatisticsDashboardKeys.totalRevenue),
                value = totalRevenue,
                accentColor = MaterialTheme.colorScheme.primary,
                onClick = onCardClick?.let { cb -> { cb(KpiCardType.REVENUE) } },
                modifier = Modifier.width(CompactKpiCardWidth).fillMaxHeight(),
            )
            KpiCard(
                label = str(StatisticsDashboardKeys.tips),
                value = totalTips,
                accentColor = MaterialTheme.colorScheme.tertiary,
                onClick = onCardClick?.let { cb -> { cb(KpiCardType.TIPS) } },
                modifier = Modifier.width(CompactKpiCardWidth).fillMaxHeight(),
            )
            KpiCard(
                label = str(StatisticsDashboardKeys.billsLabel),
                value = totalBillCount.toString(),
                accentColor = MaterialTheme.colorScheme.secondary,
                onClick = onCardClick?.let { cb -> { cb(KpiCardType.BILLS) } },
                modifier = Modifier.width(CompactKpiCardWidth).fillMaxHeight(),
            )
            KpiCard(
                label = str(StatisticsDashboardKeys.canceled),
                value = canceledRevenue,
                accentColor = MaterialTheme.colorScheme.error,
                onClick = onCardClick?.let { cb -> { cb(KpiCardType.CANCELED) } },
                modifier = Modifier.width(CompactKpiCardWidth).fillMaxHeight(),
            )
        }
    } else {
        // Wrapping grid: cards drop to a second row before an amount is squeezed to an ellipsis.
        val openLabel = str(StatisticsDashboardKeys.openAmount)
        val revenueLabel = str(StatisticsDashboardKeys.totalRevenue)
        val tipsLabel = str(StatisticsDashboardKeys.tips)
        val billsLabel = str(StatisticsDashboardKeys.billsLabel)
        val canceledLabel = str(StatisticsDashboardKeys.canceled)
        val tertiary = MaterialTheme.colorScheme.tertiary
        val primary = MaterialTheme.colorScheme.primary
        val secondary = MaterialTheme.colorScheme.secondary
        val error = MaterialTheme.colorScheme.error
        val items = mutableListOf<@Composable (Modifier) -> Unit>()
        if (hasOpenAmount) {
            items += { m -> KpiCard(label = openLabel, value = openAmount, accentColor = tertiary, modifier = m) }
        }
        items +=
            { m ->
                KpiCard(
                    label = revenueLabel,
                    value = totalRevenue,
                    accentColor = primary,
                    onClick = onCardClick?.let { cb -> { cb(KpiCardType.REVENUE) } },
                    modifier = m,
                )
            }
        items +=
            { m ->
                KpiCard(
                    label = tipsLabel,
                    value = totalTips,
                    accentColor = tertiary,
                    onClick = onCardClick?.let { cb -> { cb(KpiCardType.TIPS) } },
                    modifier = m,
                )
            }
        items +=
            { m ->
                KpiCard(
                    label = billsLabel,
                    value = totalBillCount.toString(),
                    accentColor = secondary,
                    onClick = onCardClick?.let { cb -> { cb(KpiCardType.BILLS) } },
                    modifier = m,
                )
            }
        items +=
            { m ->
                KpiCard(
                    label = canceledLabel,
                    value = canceledRevenue,
                    accentColor = error,
                    onClick = onCardClick?.let { cb -> { cb(KpiCardType.CANCELED) } },
                    modifier = m,
                )
            }
        PosKpiGrid(items = items, modifier = modifier, minItemWidth = RegularKpiCardMinWidth)
    }
}
