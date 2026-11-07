package org.example.project.admin.features.reports.presentation.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.MonthlyCloseKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosKpiGrid
import org.example.project.ui.components.chart.Sparkline
import org.example.project.ui.components.formatPercentForDisplay

/**
 * The seven period KPIs the server computes for the close, each with its daily series drawn as a
 * sparkline and its like-for-like delta. Distinct from the Berichte KPI row above it, which shows
 * four sales figures with no series: these are the closing figures (VAT total, refunds, the
 * cash-book closing balance) an accountant is handed.
 *
 * The values arrive as canonical decimal strings; money is formatted for display with
 * [currencyCode] (the response's currency), the bill count is shown as it came. The deltas follow
 * the Berichte "Vergleich" toggle ([compareEnabled]); with it off no delta is drawn.
 */
@Composable
fun CloseKpiRow(
    kpis: List<MonthlyCloseDto.KpiMetricDto>,
    currencyCode: String,
    compareEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    if (kpis.isEmpty()) return
    PosKpiGrid(
        items = kpis.map { kpi -> { m: Modifier -> CloseKpiCard(kpi, currencyCode, compareEnabled, m) } },
        modifier = modifier,
    )
}

/** The delta a close KPI card may draw: the server's figure, but only while comparison is switched on. */
internal fun closeDeltaToShow(
    compareEnabled: Boolean,
    deltaPercent: String?,
): String? = deltaPercent.takeIf { compareEnabled }

@Composable
private fun CloseKpiCard(
    kpi: MonthlyCloseDto.KpiMetricDto,
    currencyCode: String,
    compareEnabled: Boolean,
    modifier: Modifier,
) {
    PosCard(modifier = modifier.fillMaxWidth(), variant = PosCardVariant.Plain) {
        body {
            Text(str(kpiLabelKey(kpi.key)), style = MaterialTheme.typography.labelMedium)
            Text(kpi.displayValue(currencyCode), style = MaterialTheme.typography.headlineSmall)
            val delta = closeDeltaToShow(compareEnabled, kpi.deltaPercent)
            if (delta != null) {
                Text(
                    text = formatPercentForDisplay(delta, LocalAppLanguage.current),
                    color =
                        if (delta.startsWith("-")) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    style = MaterialTheme.typography.bodySmall,
                )
                // The monthly-close payload names no baseline window, so the label stays generic.
                Text(
                    text = str(AdminStringKeys.ReportsComparedToPreviousPeriod),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (kpi.dailySeries.size >= 2) {
                Spacer(Modifier.height(4.dp))
                Sparkline(kpi.dailySeries, Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * The server sends a stable key, not a label. An unknown key renders as the raw key rather than
 * crashing the screen — composeApp's `error("Unknown KPI key: …")` would take the whole Berichte
 * page down if the server ever added an eighth KPI before the client knew about it.
 */
private fun kpiLabelKey(key: String): StringKey =
    when (key) {
        "grossRevenue" -> MonthlyCloseKeys.kpiGrossRevenue
        "netRevenue" -> MonthlyCloseKeys.kpiNetRevenue
        "vatTotal" -> MonthlyCloseKeys.kpiVatTotal
        "billCount" -> MonthlyCloseKeys.kpiBillCount
        "refunds" -> MonthlyCloseKeys.kpiRefunds
        "tips" -> MonthlyCloseKeys.kpiTips
        "kassenbuchClosing" -> MonthlyCloseKeys.kpiKassenbuchClosing
        else -> StringKey(key)
    }
