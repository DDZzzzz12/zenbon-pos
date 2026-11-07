package org.example.project.features.reports.presentation.components.dailyreport

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.StatisticsDashboardKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.getLocalizedName
import org.example.project.core.utils.toDecimalOrZero
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.money.Decimal
import org.example.project.ui.components.BillRelationChips
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.theme.PosSpacing

// Column weights shared by DesktopBillRow and the TransactionList header so the two stay aligned.
internal const val BILL_COL_NUMBER_WEIGHT = 1.5f
internal const val BILL_COL_TABLE_WEIGHT = 0.8f
internal const val BILL_COL_EMPLOYEE_WEIGHT = 1.8f
internal const val BILL_COL_PAYMENT_WEIGHT = 1.4f
internal const val BILL_COL_AMOUNT_WEIGHT = 1.1f
internal const val BILL_COL_TIPS_WEIGHT = 0.9f

@Composable
fun BillTransactionRow(
    bill: BillDto.BillSummaryDto,
    isCompact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
    showTableNumber: Boolean = true,
    horizontalInset: Dp? = null,
) {
    val timeText = formatBillTime(bill.endTime)
    val language = LocalAppLanguage.current
    val paymentText = bill.paymentType.joinToString(" + ") { it.getLocalizedName(language) }
    // Compared as a Decimal: the server may send "0.0000", which a string check read as a tip ("+0,00 €").
    val hasTips = bill.tips.toDecimalOrZero() > Decimal.ZERO
    val tableLabel =
        if (showTableNumber) {
            bill.tableType ?: bill.tableNumber?.let { "${str(StatisticsDashboardKeys.table)} $it" }
        } else {
            null
        }

    if (isCompact) {
        CompactBillRow(
            bill = bill,
            timeText = timeText,
            paymentText = paymentText,
            hasTips = hasTips,
            onClick = onClick,
            modifier = modifier,
            showDivider = showDivider,
            tableLabel = tableLabel,
            horizontalInset = horizontalInset,
        )
    } else {
        DesktopBillRow(
            bill = bill,
            timeText = timeText,
            paymentText = paymentText,
            hasTips = hasTips,
            onClick = onClick,
            modifier = modifier,
            showDivider = showDivider,
            tableLabel = tableLabel,
            horizontalInset = horizontalInset,
        )
    }
}

@Composable
private fun DesktopBillRow(
    bill: BillDto.BillSummaryDto,
    timeText: String,
    paymentText: String,
    hasTips: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean,
    tableLabel: String? = null,
    horizontalInset: Dp? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onClick() }
                    .padding(horizontal = horizontalInset ?: 16.dp, vertical = PosSpacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Bill number, with its relation chips ("Storno zu RE-…", "Storniert", …) beneath
            Column(modifier = Modifier.weight(BILL_COL_NUMBER_WEIGHT)) {
                Text(
                    text = "#${bill.billNumber}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                BillRelationChips(relations = bill.relations, modifier = Modifier.padding(top = 2.dp), isTraining = bill.isTraining)
            }
            // Table info
            if (tableLabel != null) {
                Text(
                    text = tableLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(BILL_COL_TABLE_WEIGHT),
                )
            }
            // Employee + time
            Text(
                text = "${bill.employeeNumber} · $timeText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(BILL_COL_EMPLOYEE_WEIGHT),
            )
            // Payment type
            Text(
                text = paymentText.ifBlank { "-" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(BILL_COL_PAYMENT_WEIGHT),
            )
            // Amount
            Text(
                text = bill.paidAmount.toFormattedCurrency(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(BILL_COL_AMOUNT_WEIGHT),
            )
            // Tips (always present to keep consistent column layout)
            Text(
                text = if (hasTips) "+${bill.tips.toFormattedCurrency()}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.End,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(BILL_COL_TIPS_WEIGHT),
            )
        }
        if (showDivider) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun CompactBillRow(
    bill: BillDto.BillSummaryDto,
    timeText: String,
    paymentText: String,
    hasTips: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean,
    tableLabel: String? = null,
    horizontalInset: Dp? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        PosCard(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalInset ?: 12.dp, vertical = 4.dp),
            onClick = onClick,
            variant = PosCardVariant.Plain,
        ) {
            body {
                // Row 1: bill number + amount
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "#${bill.billNumber}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = bill.paidAmount.toFormattedCurrency(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (hasTips) {
                            Text(
                                text = "+${bill.tips.toFormattedCurrency()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                // Relation chips ("Storno zu RE-…", "Storniert", …), only when there are any
                BillRelationChips(relations = bill.relations, modifier = Modifier.padding(top = 2.dp), isTraining = bill.isTraining)
                // Row 2: employee + time + payment
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text =
                            buildString {
                                append("${bill.employeeNumber} · $timeText")
                                if (tableLabel != null) append(" · $tableLabel")
                            },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = paymentText.ifBlank { "-" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            )
        }
    }
}

private fun formatBillTime(dateTimeString: String): String =
    try {
        val dateTime = LocalDateTime.parse(dateTimeString)
        "${dateTime.hour.toString().padStart(2, '0')}:${dateTime.minute.toString().padStart(2, '0')}"
    } catch (e: Exception) {
        dateTimeString.takeLast(5)
    }
