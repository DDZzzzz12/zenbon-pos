package org.example.project.admin.features.invoices.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.belegfluss.InvoiceRowProgress
import org.example.project.admin.core.presentation.belegfluss.InvoiceRowStatus
import org.example.project.admin.core.presentation.components.adminTouchSize
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.document.InvoiceListDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.EMPTY_VALUE
import org.example.project.core.utils.orDash
import org.example.project.ui.components.PosMoneyText
import org.example.project.ui.theme.PosSpacing

/**
 * The phone/tablet shape of the invoice table: one row per invoice (≥ 76dp), the supplier and the
 * amount on the first line, "number · fällig …" beneath, then status and progress, with the row's
 * primary action under the amount. The nine table columns do not fit below 900dp.
 */
@Composable
internal fun InvoiceCardList(
    items: List<InvoiceListDto.RowDto>,
    actionsEnabled: Boolean,
    handlers: InvoiceRowHandlers,
) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        items.forEach { row ->
            InvoiceListRow(row = row, actionsEnabled = actionsEnabled, handlers = handlers)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun InvoiceListRow(
    row: InvoiceListDto.RowDto,
    actionsEnabled: Boolean,
    handlers: InvoiceRowHandlers,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(vertical = PosSpacing.lg),
        horizontalArrangement = Arrangement.spacedBy(PosSpacing.lg),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PosSpacing.sm)) {
            if (row.bucket == InvoiceListDto.Bucket.ZU_ERFASSEN && row.supplierName.isNullOrBlank()) {
                Text(
                    uncapturedScanTitle(row),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(row.supplierName.orDash(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            Text(
                text = invoiceDetailLine(row),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (row.cancelled) InvoiceCancelledMarker()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PosSpacing.lg)) {
                InvoiceRowStatus(row.bucket)
                InvoiceRowProgress(row.bucket)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(PosSpacing.sm)) {
            // The open residual is the figure a payable row is about; before that the gross total is.
            val amount = row.openAmount ?: row.grossTotal
            if (amount == null) {
                Text(EMPTY_VALUE, style = MaterialTheme.typography.titleSmall)
            } else {
                PosMoneyText(
                    amount = amount,
                    currencyCode = row.currency,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(PosSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                InvoiceActionButtons(row = row, actionsEnabled = actionsEnabled, handlers = handlers, height = adminTouchSize(40.dp), filled = false)
            }
        }
    }
}

/** "RE-2026-10441 · fällig 16.10." — the number and the due date, each omitted when the row has none. */
@Composable
private fun invoiceDetailLine(row: InvoiceListDto.RowDto): String {
    val number = row.invoiceNumber?.takeIf { it.isNotBlank() } ?: str(AdminStringKeys.BelegflowNotCaptured)
    val due = invoiceListDate(row.dueDate, LocalAppLanguage.current)
    return str(AdminStringKeys.BelegflowNumberDue, number, due)
}

/** "storniert": the invoice has been cancelled by a Storno; shown in every bucket it appears in. */
@Composable
internal fun InvoiceCancelledMarker() {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(
            str(AdminStringKeys.InvoicesCancelledMarker),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
