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
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.TableOrderHistoryKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.formatReadableDateTime
import org.example.project.core.utils.rememberLanguage
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.ui.components.OrderItemStatus
import org.example.project.ui.components.PosAutoSizeText
import org.example.project.ui.components.PosOrderItemCard

/**
 * Detail panel for a selected order, intended for tablet/desktop right panel.
 *
 * Shows full order information including header, info grid, all items with
 * extras, and a footer with totals.
 *
 * @param order The order to display in detail
 * @param modifier Modifier for the root element
 */
@Composable
fun PosOrderDetailPanel(
    order: OrderDto.OrderResponseDto,
    modifier: Modifier = Modifier,
) {
    val language by rememberLanguage()
    val scrollState = rememberScrollState()

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
            // Header: Order number + status chip with left accent
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
                            .background(MaterialTheme.colorScheme.primary),
                )
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${str(TableOrderHistoryKeys.orderNumber)} #${order.orderId}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )

                    val statusLabel = resolveDetailOrderStatus(order)
                    val statusColors = resolveDetailOrderStatusColors(order)
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = statusColors.first,
                    ) {
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = statusColors.second,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            HorizontalDivider()

            // Info row: Date | Time | Employee | Items
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                InfoColumn(
                    label = str(CommonKeys.date),
                    value = order.orderTime.formatReadableDateTime(language, showTime = false),
                    modifier = Modifier.weight(1f),
                )
                InfoColumn(
                    label = str(CommonKeys.time),
                    value = order.orderTime.formatReadableDateTime(language, showTime = true),
                    modifier = Modifier.weight(1f),
                )
                InfoColumn(
                    label = str(TableOrderHistoryKeys.employeeNumber),
                    value = order.employeeId,
                    modifier = Modifier.weight(1f),
                )
                InfoColumn(
                    label = str(CommonKeys.quantity),
                    value = order.orderItems.size.toString(),
                    modifier = Modifier.weight(1f),
                )
            }

            HorizontalDivider()

            // "Order Items" section header
            Text(
                text = str(TableOrderHistoryKeys.ordersTab),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            // Full item list
            order.orderItems.forEach { item ->
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

            // Footer: Order Total
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
                    text = order.orderTotal.toFormattedCurrency(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Info column for the detail header grid.
 */
@Composable
private fun InfoColumn(
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

@Composable
private fun resolveDetailOrderStatus(order: OrderDto.OrderResponseDto): String {
    val allPaid = order.orderItems.all { it.itemStatus.equals("PAID", ignoreCase = true) }
    val anyOpen = order.orderItems.any { it.itemStatus.equals("OPEN", ignoreCase = true) }
    return when {
        allPaid -> str(TableOrderHistoryKeys.statusPaid)
        anyOpen -> str(TableOrderHistoryKeys.statusOpen)
        else -> str(TableOrderHistoryKeys.statusPaid)
    }
}

@Composable
private fun resolveDetailOrderStatusColors(order: OrderDto.OrderResponseDto): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> {
    val allPaid = order.orderItems.all { it.itemStatus.equals("PAID", ignoreCase = true) }
    val anyOpen = order.orderItems.any { it.itemStatus.equals("OPEN", ignoreCase = true) }
    return when {
        allPaid -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        anyOpen -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
}
