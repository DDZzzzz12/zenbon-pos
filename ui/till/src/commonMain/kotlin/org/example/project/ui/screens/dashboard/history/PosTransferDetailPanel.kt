package org.example.project.ui.screens.dashboard.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.order.ItemTransferHistoryDto
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.TableOrderHistoryKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.formatReadableDateTime
import org.example.project.core.utils.rememberLanguage
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.ui.components.OrderItemStatus
import org.example.project.ui.components.PosAutoSizeText
import org.example.project.ui.components.PosOrderItemCard
import org.example.project.ui.theme.PosTheme

/**
 * Detail panel for a selected transfer, intended for tablet/desktop right panel.
 *
 * Shows full transfer information including header, info grid, all items, and footer with total.
 *
 * @param transfer The transfer to display in detail
 * @param modifier Modifier for the root element
 */
@Composable
fun PosTransferDetailPanel(
    transfer: ItemTransferHistoryDto.ItemTransferHistoryResponseDto,
    modifier: Modifier = Modifier,
) {
    val language by rememberLanguage()
    val scrollState = rememberScrollState()

    val isIncoming = transfer.direction.uppercase() == "IN"
    val colorScheme = MaterialTheme.colorScheme
    val accentColor = if (isIncoming) PosTheme.colors.posReserved else colorScheme.outline
    val chipBg = if (isIncoming) colorScheme.primaryContainer else colorScheme.surfaceContainerHighest
    val chipTextColor = if (isIncoming) colorScheme.onPrimaryContainer else colorScheme.onSurfaceVariant
    val chipLabel = if (isIncoming) str(TableOrderHistoryKeys.transferredIn) else str(TableOrderHistoryKeys.transferredOut)
    val titleText =
        if (isIncoming) {
            str(TableOrderHistoryKeys.transferFromTable, transfer.fromTableNumber)
        } else {
            str(TableOrderHistoryKeys.transferToTable, transfer.toTableNumber)
        }
    val arrowSymbol = if (isIncoming) "↓" else "↑"
    val sectionHeader = if (isIncoming) str(TableOrderHistoryKeys.transferredIn) else str(TableOrderHistoryKeys.transferredOut)

    Surface(
        modifier = modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border =
            androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
        ) {
            // Header: direction arrow + title + status chip with left accent bar
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .width(4.dp)
                            .fillMaxHeight()
                            .background(accentColor),
                )
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = arrowSymbol,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = accentColor,
                        )
                        Text(
                            text = titleText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = chipBg,
                    ) {
                        Text(
                            text = chipLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = chipTextColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            HorizontalDivider()

            // Info row: Date | Time | Employee | Items count
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TransferInfoColumn(
                    label = str(CommonKeys.date),
                    value = transfer.transferTime.formatReadableDateTime(language, showTime = false),
                    modifier = Modifier.weight(1f),
                )
                TransferInfoColumn(
                    label = str(CommonKeys.time),
                    value = transfer.transferTime.formatReadableDateTime(language, showTime = true),
                    modifier = Modifier.weight(1f),
                )
                TransferInfoColumn(
                    label = str(TableOrderHistoryKeys.transferredBy),
                    value = transfer.employeeName,
                    modifier = Modifier.weight(1f),
                )
                TransferInfoColumn(
                    label = str(CommonKeys.quantity),
                    value = transfer.items.size.toString(),
                    modifier = Modifier.weight(1f),
                )
            }

            HorizontalDivider()

            // Section header: "Transferred In" or "Transferred Out"
            Text(
                text = sectionHeader,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            // Full item list
            transfer.items.forEach { item ->
                PosOrderItemCard(
                    quantity = item.quantity,
                    productName = item.product.productName,
                    price = item.finalPrice,
                    extras = item.extras?.joinToString(", ") { it.name },
                    sideNotes = item.sideNotes,
                    status =
                        when (item.itemStatus.uppercase()) {
                            "PAID" -> OrderItemStatus.PAID
                            "CANCELLED" -> OrderItemStatus.CANCELLED
                            else -> OrderItemStatus.OPEN
                        },
                    isCancelled = item.itemStatus.uppercase() == "CANCELLED",
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Footer: Total in accent color
            HorizontalDivider()
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = str(TableOrderHistoryKeys.orderTotal),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = transfer.totalAmount.toFormattedCurrency(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                )
            }
        }
    }
}

/**
 * Info column for the transfer detail header grid.
 */
@Composable
private fun TransferInfoColumn(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PosAutoSizeText(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}
