package org.example.project.admin.features.voucher.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.voucher.VoucherDto
import org.example.project.core.presentation.strings.keys.VoucherManagementKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.EMPTY_VALUE
import org.example.project.features.reports.presentation.components.dailyreport.KpiCard
import org.example.project.ui.components.formatMoneyForDisplay
import org.example.project.ui.layout.PaneBreakpoints

/**
 * Four KPIs. The first two are derived from the loaded period lists and move with the stepper and
 * the employee filter; the last two come from `/statistics`, which is lifetime and shop-wide
 * (`getVoucherStatistics` accepts an employeeId and never uses it), so they are labelled "gesamt"
 * and must never be described as period or per-employee figures.
 *
 * A missing statistics response renders a placeholder rather than 0 — a zero would read as
 * "nothing outstanding", which is a different and dangerous claim.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoucherKpiRow(
    issuedAmount: String,
    issuedCount: Int,
    redeemedAmount: String,
    redeemedCount: Int,
    statistics: VoucherDto.VoucherStatisticsDto?,
    currencyCode: String,
    modifier: Modifier = Modifier,
) {
    // Two accents, not one: the caption text alone is a weak signal for the distinction that
    // matters most here — the first two cards move with the period stepper and the employee filter,
    // the last two are lifetime and shop-wide and cannot narrow at all. Reading a "gesamt" figure as
    // a period figure is precisely the error the split row exists to prevent.
    val periodAccent = MaterialTheme.colorScheme.primary
    val lifetimeAccent = MaterialTheme.colorScheme.outline
    val overall = str(AdminStringKeys.VouchersKpiOverall)
    val placeholder = EMPTY_VALUE

    val cards: @Composable (Modifier) -> Unit = { cardModifier ->
        KpiCard(
            label = str(VoucherManagementKeys.createdVouchers),
            value = formatMoneyForDisplay(issuedAmount, currencyCode),
            accentColor = periodAccent,
            modifier = cardModifier,
            caption = str(AdminStringKeys.VouchersKpiInPeriod, issuedCount.toString()),
        )
        KpiCard(
            label = str(VoucherManagementKeys.redeemedVouchers),
            value = formatMoneyForDisplay(redeemedAmount, currencyCode),
            accentColor = periodAccent,
            modifier = cardModifier,
            caption = str(AdminStringKeys.VouchersKpiInPeriod, redeemedCount.toString()),
        )
        KpiCard(
            label = str(VoucherManagementKeys.activeVouchers),
            // A count, not money — never routed through the money formatter.
            value = statistics?.activeVouchers?.toString() ?: placeholder,
            accentColor = lifetimeAccent,
            modifier = cardModifier,
            caption = overall,
        )
        KpiCard(
            label = str(AdminStringKeys.VouchersKpiOpenValue),
            value = statistics?.let { formatMoneyForDisplay(it.totalValueRemaining, currencyCode) } ?: placeholder,
            accentColor = lifetimeAccent,
            modifier = cardModifier,
            caption = overall,
        )
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth < PaneBreakpoints.PHONE_MAX) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                maxItemsInEachRow = 2,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { cards(Modifier.weight(1f)) }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) { cards(Modifier.weight(1f)) }
        }
    }
}
