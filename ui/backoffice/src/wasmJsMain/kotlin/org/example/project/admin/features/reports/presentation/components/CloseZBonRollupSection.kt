package org.example.project.admin.features.reports.presentation.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import org.example.project.core.presentation.strings.keys.MonthlyCloseKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.formatLocalDateTimeShort
import org.example.project.ui.components.PosAdaptiveColumn
import org.example.project.ui.components.PosAdaptiveDataTable
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardRole
import org.example.project.ui.components.PosTableAlign
import org.example.project.ui.components.PosTableColumn
import org.example.project.ui.components.PosTableMin
import org.example.project.ui.components.PosTableRow
import org.example.project.ui.components.formatMoneyForDisplay

/**
 * Every Z-Bon in the period with its takings, over the chain timeline. "Rückerstattungen" is the
 * stored fiscal Storno total with its "davon Zahlartwechsel" beside it, so the KPI "Rückerstattungen
 * ohne Zahlartwechsel" is visibly their difference (admin18 NOTE-20). The totals row is the
 * server's own `rollup.totals` — it is never re-summed here, because the server derives VAT by
 * subtraction and a client-side re-sum could disagree with the receipt.
 */
@Composable
fun CloseZBonRollupSection(
    periodStart: String,
    periodEnd: String,
    rollup: MonthlyCloseDto.ZBonRollupDto,
    currencyCode: String,
    modifier: Modifier = Modifier,
) {
    fun money(amount: String) = formatMoneyForDisplay(amount, currencyCode)

    val fallback = LocalDateTime(1970, 1, 1, 0, 0)
    val start = runCatching { LocalDateTime.parse(periodStart) }.getOrElse { fallback }
    val end = runCatching { LocalDateTime.parse(periodEnd) }.getOrElse { fallback }

    val columns =
        listOf(
            PosAdaptiveColumn(
                PosTableColumn(str(MonthlyCloseKeys.colZBonNumber), PosTableAlign.Start, weight = 1.3f, minWidth = PosTableMin.Id, nowrap = true),
                PosCardRole.Title,
            ),
            PosAdaptiveColumn(
                PosTableColumn(str(MonthlyCloseKeys.colStartTime), PosTableAlign.Start, weight = 1.3f, minWidth = PosTableMin.DateTime, nowrap = true),
            ),
            PosAdaptiveColumn(
                PosTableColumn(str(MonthlyCloseKeys.colEndTime), PosTableAlign.Start, weight = 1.3f, minWidth = PosTableMin.DateTime, nowrap = true),
            ),
            PosAdaptiveColumn(
                PosTableColumn(str(MonthlyCloseKeys.colGrossRevenue), PosTableAlign.End, weight = 1.1f, minWidth = PosTableMin.Money, nowrap = true),
                PosCardRole.Trailing,
            ),
            PosAdaptiveColumn(
                PosTableColumn(str(MonthlyCloseKeys.colNetRevenue), PosTableAlign.End, weight = 1.1f, minWidth = PosTableMin.Money, nowrap = true),
            ),
            PosAdaptiveColumn(PosTableColumn(str(MonthlyCloseKeys.colVatTotal), PosTableAlign.End, weight = 1.1f, minWidth = PosTableMin.Money, nowrap = true)),
            PosAdaptiveColumn(PosTableColumn(str(MonthlyCloseKeys.colTips), PosTableAlign.End, weight = 1.1f, minWidth = PosTableMin.Money, nowrap = true)),
            PosAdaptiveColumn(PosTableColumn(str(MonthlyCloseKeys.colRefunds), PosTableAlign.End, weight = 1.1f, minWidth = PosTableMin.Money, nowrap = true)),
            PosAdaptiveColumn(
                PosTableColumn(str(MonthlyCloseKeys.colRebookStornos), PosTableAlign.End, weight = 1.1f, minWidth = PosTableMin.Money, nowrap = true),
            ),
            PosAdaptiveColumn(
                PosTableColumn(str(MonthlyCloseKeys.colCancellations), PosTableAlign.End, weight = 1.1f, minWidth = PosTableMin.Money, nowrap = true),
            ),
        )
    val rows =
        rollup.rows.map { row ->
            PosTableRow(
                listOf(
                    row.zBonNumber,
                    row.startTime.formatLocalDateTimeShort(),
                    row.endTime.formatLocalDateTimeShort(),
                    money(row.grossRevenue),
                    money(row.netRevenue),
                    money(row.vatTotal),
                    money(row.tips),
                    money(row.refunds),
                    money(row.rebookStornos),
                    money(row.cancellations),
                ),
            )
        } +
            PosTableRow(
                cells =
                    listOf(
                        str(MonthlyCloseKeys.totalsRowLabel),
                        "",
                        "",
                        money(rollup.totals.grossRevenue),
                        money(rollup.totals.netRevenue),
                        money(rollup.totals.vatTotal),
                        money(rollup.totals.tips),
                        money(rollup.totals.refunds),
                        money(rollup.totals.rebookStornos),
                        money(rollup.totals.cancellations),
                    ),
                emphasized = true,
            )

    PosCard(modifier = modifier.fillMaxWidth()) {
        body {
            Text(
                text = str(MonthlyCloseKeys.sectionZBonRollup),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            ZBonChainTimeline(
                rangeStart = start,
                rangeEnd = end,
                rows = rollup.rows,
                gaps = rollup.chainGaps,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = str(MonthlyCloseKeys.timelineCaption),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PosAdaptiveDataTable(columns, rows)
            // Stored Umsatz ≠ the bills' Umsatz (pre-1.1.0 closings with promotions): say so and by
            // how much, rather than leave two totals on one page that silently disagree.
            rollup.revenueReconciliationArgs(currencyCode)?.let { args ->
                Text(
                    text = str(MonthlyCloseKeys.revenueReconciliation, *args.toTypedArray()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
