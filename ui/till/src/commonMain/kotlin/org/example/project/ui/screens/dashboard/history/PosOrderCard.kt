package org.example.project.ui.screens.dashboard.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosOrderItemCard
import org.example.project.ui.theme.PosTheme

/**
 * Order card with left accent bar, items list, and status badges.
 *
 * Designed for use in order history lists. Uses forEach for item rendering
 * (not lazy) to avoid nested scrollable issues inside LazyColumn.
 *
 * @param order The order data to display
 * @param isSelected Whether this card is currently selected (highlights border/background)
 * @param onClick Callback when the card is tapped
 * @param expanded Whether the order items list is visible (default true)
 * @param onExpandToggle Callback when the expand/collapse icon is tapped; if null, no toggle icon is shown
 * @param modifier Modifier for the root element
 */
@Composable
fun PosOrderCard(
    order: OrderDto.OrderResponseDto,
    isSelected: Boolean = false,
    onClick: () -> Unit = {},
    expanded: Boolean = true,
    onExpandToggle: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val language by rememberLanguage()

    val borderColor =
        if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        }
    val borderWidth = if (isSelected) 2.dp else 1.dp
    val containerColor =
        if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        }

    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(borderWidth, borderColor),
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // Left accent bar
            Box(
                modifier =
                    Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        ),
            )

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Header row: order number + total price + expand toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${str(TableOrderHistoryKeys.orderNumber)} #${order.orderId}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = order.orderTotal.toFormattedCurrency(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (onExpandToggle != null) {
                        PosIconButton(
                            icon = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            onClick = onExpandToggle,
                            size = 32.dp,
                        )
                    }
                }

                // Subtitle: timestamp + employee
                PosAutoSizeText(
                    text = "${order.orderTime.formatReadableDateTime(
                        language,
                    )} | ${str(TableOrderHistoryKeys.orderCreatedBy, order.employeeId)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Order status chip
                val orderStatusLabel = resolveOrderStatusLabel(order)
                val orderStatusColor = resolveOrderStatusColor(order)
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = orderStatusColor.first,
                ) {
                    Text(
                        text = orderStatusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = orderStatusColor.second,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }

                // Item list (forEach, not lazy) — collapsible via expanded flag
                AnimatedVisibility(
                    visible = expanded && order.orderItems.isNotEmpty(),
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

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
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // Footer: summary counts + item count + total
                        OrderFooter(order = order)
                    }
                }
            }
        }
    }
}

/**
 * Footer row showing summary counts and total.
 */
@Composable
private fun OrderFooter(order: OrderDto.OrderResponseDto) {
    val paidCount = order.orderItems.count { it.itemStatus.equals("PAID", ignoreCase = true) }
    val openCount = order.orderItems.count { it.itemStatus.equals("OPEN", ignoreCase = true) }
    val cancelledCount = order.orderItems.count { it.itemStatus.equals("CANCELLED", ignoreCase = true) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Summary counts
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (paidCount > 0) {
                Text(
                    text = "$paidCount ${str(TableOrderHistoryKeys.statusPaid)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = PosTheme.colors.posAvailable,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (openCount > 0) {
                Text(
                    text = "$openCount ${str(TableOrderHistoryKeys.statusOpen)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = PosTheme.colors.posOccupied,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (cancelledCount > 0) {
                Text(
                    text = "$cancelledCount ${str(TableOrderHistoryKeys.statusCancelled)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = PosTheme.colors.posError,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // Item count + total
        Text(
            text = "${order.orderItems.size} ${str(CommonKeys.quantity)} | ${order.orderTotal.toFormattedCurrency()}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Resolve human-readable status label for an order based on its items.
 */
@Composable
private fun resolveOrderStatusLabel(order: OrderDto.OrderResponseDto): String {
    val allPaid = order.orderItems.all { it.itemStatus.equals("PAID", ignoreCase = true) }
    val anyOpen = order.orderItems.any { it.itemStatus.equals("OPEN", ignoreCase = true) }
    return when {
        allPaid -> str(TableOrderHistoryKeys.statusPaid)
        anyOpen -> str(TableOrderHistoryKeys.statusOpen)
        else -> str(TableOrderHistoryKeys.statusPaid)
    }
}

/**
 * Resolve colors for order status chip.
 * Returns Pair(backgroundColor, textColor).
 */
@Composable
private fun resolveOrderStatusColor(order: OrderDto.OrderResponseDto): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> {
    val allPaid = order.orderItems.all { it.itemStatus.equals("PAID", ignoreCase = true) }
    val anyOpen = order.orderItems.any { it.itemStatus.equals("OPEN", ignoreCase = true) }
    return when {
        allPaid -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        anyOpen -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
}
