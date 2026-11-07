package org.example.project.admin.features.reports.presentation.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.statistics.PaymentMethodSummaryDto
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.domain.dto.statistics.VatSummaryDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.MonthlyCloseKeys
import org.example.project.core.presentation.strings.keys.ReportsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.getLocalizedName
import org.example.project.core.utils.paymentNameSortKey
import org.example.project.money.Decimal
import org.example.project.ui.components.PosAdaptiveColumn
import org.example.project.ui.components.PosAdaptiveDataTable
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardRole
import org.example.project.ui.components.PosTableAlign
import org.example.project.ui.components.PosTableColumn
import org.example.project.ui.components.PosTableMin
import org.example.project.ui.components.PosTableRow
import org.example.project.ui.components.formatMoneyForDisplay
import org.example.project.ui.components.formatPercentForDisplay

@Composable
fun FinancialSection(financial: StatisticsDto.FinancialOverviewResponseDto) {
    val currency = financial.currency

    PosCard(modifier = Modifier.fillMaxWidth()) {
        body {
            Text(
                text = str(AdminStringKeys.ReportsFinancialTitle),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            Text(
                text = str(AdminStringKeys.ReportsVatTitle),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
            VatTable(financial.vatBreakdown, currency)

            HorizontalDivider()

            Text(
                text = str(AdminStringKeys.ReportsPaymentsTitle),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
            PaymentsTable(financial.paymentMethodBreakdown, currency)
            Text(
                text = str(AdminStringKeys.ReportsPaymentsTipsHint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ReceivableClearedNote(financial.receivableCleared, currency)
        }
    }
}

/**
 * The receivable clearings inside the payment table that the Umsatz does not hold — the turnover was
 * booked when the invoice was issued, as on the Z-Bon. Nothing is shown when there are none.
 */
@Composable
private fun ReceivableClearedNote(
    receivableCleared: String,
    currency: String,
) {
    if (Decimal.parseOrNull(receivableCleared)?.isZero != false) return
    Text(
        text = "${str(ReportsKeys.receivableCleared)}: ${formatMoneyForDisplay(receivableCleared, currency)}",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        text = str(ReportsKeys.receivableClearedNote),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun VatTable(
    vatBreakdown: Map<String, VatSummaryDto>,
    currency: String,
) {
    val language = LocalAppLanguage.current
    val columns =
        listOf(
            PosAdaptiveColumn(PosTableColumn(str(AdminStringKeys.ReportsVatRate), PosTableAlign.Start, weight = 1.4f), PosCardRole.Title),
            PosAdaptiveColumn(PosTableColumn(str(AdminStringKeys.ReportsVatNet), PosTableAlign.End, minWidth = PosTableMin.Money, nowrap = true)),
            PosAdaptiveColumn(PosTableColumn(str(AdminStringKeys.ReportsVatVat), PosTableAlign.End, minWidth = PosTableMin.Money, nowrap = true)),
            PosAdaptiveColumn(
                PosTableColumn(str(AdminStringKeys.ReportsVatGross), PosTableAlign.End, minWidth = PosTableMin.Money, nowrap = true),
                PosCardRole.Trailing,
            ),
            PosAdaptiveColumn(PosTableColumn(str(MonthlyCloseKeys.colTransactionCount), PosTableAlign.End)),
        )
    val entries = vatBreakdown.values.toList()
    val rows =
        entries.map { v ->
            PosTableRow(
                listOf(
                    formatPercentForDisplay(v.vatRate, language),
                    formatMoneyForDisplay(v.netAmount, currency),
                    formatMoneyForDisplay(v.vatAmount, currency),
                    formatMoneyForDisplay(v.totalAmount, currency),
                    v.transactionCount.toString(),
                ),
            )
        } +
            PosTableRow(
                cells =
                    listOf(
                        str(AdminStringKeys.ReportsTotalRow),
                        formatMoneyForDisplay(sumMoneyOrNull(entries.map { it.netAmount }) ?: "", currency),
                        formatMoneyForDisplay(sumMoneyOrNull(entries.map { it.vatAmount }) ?: "", currency),
                        formatMoneyForDisplay(sumMoneyOrNull(entries.map { it.totalAmount }) ?: "", currency),
                        entries.sumOf { it.transactionCount }.toString(),
                    ),
                emphasized = true,
            )
    PosAdaptiveDataTable(columns, rows)
}

@Composable
private fun PaymentsTable(
    paymentBreakdown: Map<String, PaymentMethodSummaryDto>,
    currency: String,
) {
    val language = LocalAppLanguage.current
    val columns =
        listOf(
            PosAdaptiveColumn(PosTableColumn(str(AdminStringKeys.ReportsPaymentMethod), PosTableAlign.Start, weight = 1.4f), PosCardRole.Title),
            PosAdaptiveColumn(
                PosTableColumn(str(AdminStringKeys.ReportsVatGross), PosTableAlign.End, minWidth = PosTableMin.Money, nowrap = true),
                PosCardRole.Trailing,
            ),
            PosAdaptiveColumn(PosTableColumn(str(AdminStringKeys.ReportsShareOfTakings), PosTableAlign.End, nowrap = true)),
            PosAdaptiveColumn(PosTableColumn(str(MonthlyCloseKeys.colTransactionCount), PosTableAlign.End)),
            PosAdaptiveColumn(
                PosTableColumn(str(MonthlyCloseKeys.colAvgTransaction), PosTableAlign.End, weight = 1.2f, minWidth = PosTableMin.Money, nowrap = true),
            ),
        )
    // Highest takings first, as :platform-money Decimal (an unparseable amount sorts last); equal amounts by name.
    val entries =
        paymentBreakdown.values.sortedWith(
            compareByDescending<PaymentMethodSummaryDto> { Decimal.parseOrNull(it.totalAmount) }
                .thenBy { paymentNameSortKey(it.getLocalizedName(language)) },
        )
    val rows =
        entries.map { p ->
            PosTableRow(
                listOf(
                    p.getLocalizedName(language),
                    formatMoneyForDisplay(p.totalAmount, currency),
                    formatPercentForDisplay(p.revenuePercentage, language),
                    p.transactionCount.toString(),
                    formatMoneyForDisplay(p.averageTransactionValue, currency),
                ),
            )
        } +
            PosTableRow(
                cells =
                    listOf(
                        str(AdminStringKeys.ReportsTotalRow),
                        formatMoneyForDisplay(sumMoneyOrNull(entries.map { it.totalAmount }) ?: "", currency),
                        sumMoneyOrNull(entries.map { it.revenuePercentage })?.let { formatPercentForDisplay(it, language) } ?: "",
                        entries.sumOf { it.transactionCount }.toString(),
                        // An average of averages is not a number the server computed; leave it blank.
                        "",
                    ),
                emphasized = true,
            )
    PosAdaptiveDataTable(columns, rows)
}

/**
 * Sums money strings through the [Decimal] kernel (never Double). Returns the plain-string sum, or
 * null if any value is unparseable — the caller feeds that into [formatMoneyForDisplay], which
 * renders the same placeholder it uses everywhere, so a bad row degrades gracefully instead of
 * crashing the section.
 */
internal fun sumMoneyOrNull(values: List<String>): String? {
    var acc = Decimal.ZERO
    for (v in values) {
        val d = Decimal.parseOrNull(v) ?: return null
        acc += d
    }
    return acc.toPlainString()
}
