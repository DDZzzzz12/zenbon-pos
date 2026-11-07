package org.example.project.admin.features.reports.presentation.components

import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.money.Decimal
import org.example.project.ui.components.PosTableRow
import org.example.project.ui.components.formatMoneyForDisplay

/**
 * The rows under the Katalog's category table that take the column to the Umsatz tile.
 *
 * The categories are what their lines were charged, net of line discounts and item promotions.
 * Bill-wide promotions belong to no line and the owner tip to no product, so each is a row of its
 * own, and the closing row is the sum they reconcile to (admin17 NOTE-11: the column used to add up to
 * the list price, 279,90 €, under an Umsatz of 258,52 €). A zero figure gets no row.
 *
 * Pure, so the arithmetic of what is shown is testable without composing the table.
 *
 * @param labels The localized row labels, in the order bill promotions, owner tip, total
 * @param columnCount The table's column count; the rows fill the quantity and growth cells blank
 */
fun catalogReconciliationRows(
    categories: StatisticsDto.CategoryPerformanceResponseDto,
    currency: String,
    labels: CatalogReconciliationLabels,
    columnCount: Int,
): List<PosTableRow> {
    fun row(
        label: String,
        amount: Decimal,
        emphasized: Boolean = false,
    ): PosTableRow {
        val cells = MutableList(columnCount) { "" }
        cells[0] = label
        cells[REVENUE_COLUMN] = formatMoneyForDisplay(amount.toPlainString(), currency)
        return PosTableRow(cells = cells, emphasized = emphasized)
    }

    val billPromotions = Decimal.parseOrNull(categories.billWidePromotions) ?: Decimal.ZERO
    val ownerTips = Decimal.parseOrNull(categories.ownerTips) ?: Decimal.ZERO
    val total = Decimal.parseOrNull(categories.reconciledRevenue) ?: Decimal.ZERO
    return buildList {
        if (!billPromotions.isZero) add(row(labels.billPromotions, -billPromotions))
        if (!ownerTips.isZero) add(row(labels.ownerTips, ownerTips))
        add(row(labels.total, total, emphasized = true))
    }
}

/** Category, quantity, revenue[, growth] — the revenue cell is the third. */
private const val REVENUE_COLUMN = 2
