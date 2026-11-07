package org.example.project.admin.features.salesinvoices.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.formatMoneyForDisplay
import org.example.project.ui.components.formatPercentForDisplay

/**
 * The invoice's lines as its PDF prints them: description, quantity, gross unit price, gross line
 * amount and VAT rate. Absent when the invoice has no lines (a draft still being typed).
 */
@Composable
internal fun InvoiceLinesSection(selected: SalesInvoiceDto.ResponseDto) {
    if (selected.lines.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionTitle(str(AdminStringKeys.SalesInvoicesLines))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < COMPACT_LINES_WIDTH) {
                CompactInvoiceLines(selected)
            } else {
                WideInvoiceLines(selected)
            }
        }
    }
}

/** Below this the five weighted cells clip a four-digit amount, so each line becomes two rows. */
private val COMPACT_LINES_WIDTH = 420.dp

/** Wide enough for "19 %" / "19,5 %", so every row's amount ends at the same x. */
private val VAT_LABEL_SLOT = 48.dp

@Composable
private fun WideInvoiceLines(selected: SalesInvoiceDto.ResponseDto) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth()) {
            HeaderCell(str(AdminStringKeys.SalesInvoicesLineDescription), 3f, TextAlign.Start)
            HeaderCell(str(AdminStringKeys.SalesInvoicesLineQuantity), 1f, TextAlign.End)
            HeaderCell(str(AdminStringKeys.SalesInvoicesLineUnitPrice), 1.6f, TextAlign.End)
            HeaderCell(str(AdminStringKeys.SalesInvoicesLineAmount), 1.6f, TextAlign.End)
            HeaderCell(str(AdminStringKeys.SalesInvoicesVatRate), 1.2f, TextAlign.End)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        selected.lines.sortedBy { it.lineNo }.forEach { line ->
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(3f)) {
                    Text(line.itemName, style = MaterialTheme.typography.bodySmall)
                    line.itemDescription?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Cell(formatInvoiceQuantity(line.quantity), 1f)
                Cell(formatMoneyForDisplay(line.grossUnitPrice, selected.currency), 1.6f)
                Cell(formatMoneyForDisplay(line.grossAmount, selected.currency), 1.6f)
                Cell(formatPercentForDisplay(line.vatRate, LocalAppLanguage.current), 1.2f)
            }
        }
    }
}

/** The phone form of the lines: name (and description) on top, quantity x unit price, amount and VAT rate below. */
@Composable
private fun CompactInvoiceLines(selected: SalesInvoiceDto.ResponseDto) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        selected.lines.sortedBy { it.lineNo }.forEach { line ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(line.itemName, style = MaterialTheme.typography.bodyMedium)
                line.itemDescription?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${formatInvoiceQuantity(line.quantity)} x ${formatMoneyForDisplay(line.grossUnitPrice, selected.currency)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = formatMoneyForDisplay(line.grossAmount, selected.currency),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // A fixed slot, so the amount's right edge does not depend on "7 %" vs "19 %".
                    Text(
                        text = formatPercentForDisplay(line.vatRate, LocalAppLanguage.current),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(VAT_LABEL_SLOT),
                    )
                }
            }
        }
    }
}

/**
 * The BG-23 VAT breakdown (rate, net, VAT, gross per rate) followed by the invoice totals — the
 * Netto/USt split an operator otherwise only finds in the PDF.
 */
@Composable
internal fun InvoiceVatAndTotalsSection(selected: SalesInvoiceDto.ResponseDto) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (selected.vatBreakdown.isNotEmpty()) {
            SectionTitle(str(AdminStringKeys.SalesInvoicesVatBreakdown))
            Row(Modifier.fillMaxWidth()) {
                HeaderCell(str(AdminStringKeys.SalesInvoicesVatRate), 1.2f, TextAlign.Start)
                HeaderCell(str(AdminStringKeys.SalesInvoicesNet), 1.6f, TextAlign.End)
                HeaderCell(str(AdminStringKeys.SalesInvoicesVat), 1.6f, TextAlign.End)
                HeaderCell(str(AdminStringKeys.SalesInvoicesGross), 1.6f, TextAlign.End)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            selected.vatBreakdown.forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    Cell(formatPercentForDisplay(row.vatRate, LocalAppLanguage.current), 1.2f, TextAlign.Start)
                    Cell(formatMoneyForDisplay(row.taxableAmount, selected.currency), 1.6f)
                    Cell(formatMoneyForDisplay(row.vatAmount, selected.currency), 1.6f)
                    Cell(formatMoneyForDisplay(row.grossAmount(), selected.currency), 1.6f)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        selected.displayNetTotal()?.let { TotalRow(str(AdminStringKeys.SalesInvoicesNetTotal), it, selected.currency) }
        selected.taxTotal?.let { TotalRow(str(AdminStringKeys.SalesInvoicesVatTotal), it, selected.currency) }
        selected.taxInclusiveTotal?.let {
            TotalRow(str(AdminStringKeys.SalesInvoicesColumnTotal), it, selected.currency, bold = true)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RowScope.HeaderCell(
    text: String,
    weight: Float,
    align: TextAlign,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align,
        modifier = Modifier.weight(weight),
    )
}

@Composable
private fun RowScope.Cell(
    text: String,
    weight: Float,
    align: TextAlign = TextAlign.End,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        textAlign = align,
        maxLines = 1,
        modifier = Modifier.weight(weight),
    )
}

@Composable
private fun TotalRow(
    label: String,
    amount: String,
    currency: String,
    bold: Boolean = false,
) {
    val weight = if (bold) FontWeight.Bold else FontWeight.Normal
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, fontWeight = weight)
        Text(
            text = formatMoneyForDisplay(amount, currency),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = weight,
        )
    }
}
