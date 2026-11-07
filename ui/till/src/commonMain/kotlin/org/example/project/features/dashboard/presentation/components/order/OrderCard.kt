package org.example.project.features.dashboard.presentation.components.order

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.keys.TableOrderHistoryKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.discountToPercentage
import org.example.project.core.utils.formatReadableDateTime
import org.example.project.core.utils.toDecimalOrZero
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.money.Decimal
import org.example.project.ui.components.PosAutoSizeText
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.theme.PosSpacing

/**
 * Individual order card with expandable order items.
 */
@Composable
fun OrderCard(
    order: OrderDto.OrderResponseDto,
    language: Language,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    PosCard(
        modifier = modifier,
        variant = PosCardVariant.Tonal,
        onClick = { expanded = !expanded },
    ) {
        body {
            // Order number and time
            Text(
                text = "${str(TableOrderHistoryKeys.orderNumber)}: ${order.orderNumber}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            Text(
                text = str(TableOrderHistoryKeys.orderTotal) + ": " + order.orderTotal.toDecimalOrZero().toFormattedCurrency(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )

            // Order time
            Text(
                text = "${str(TableOrderHistoryKeys.orderTime)}: ${order.orderTime.formatReadableDateTime(language)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Employee ID
                Text(
                    text = str(TableOrderHistoryKeys.orderCreatedBy, order.employeeId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Expand/collapse indicator
                Text(
                    text = if (expanded) "▼" else "▶",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            AnimatedVisibility(visible = expanded && order.orderItems.isNotEmpty()) {
                HorizontalDivider()
                // Order items list
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    order.orderItems.forEach { item ->
                        OrderItemRow(
                            item = item,
                            language = language,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Individual order item row.
 */
@Composable
private fun OrderItemRow(
    item: OrderItemDto.OrderItemResponseDto,
    language: Language,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Left side: Product info
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Product name and quantity
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Quantity badge
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.extraSmall,
                ) {
                    Text(
                        text = "${item.quantity}x",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = PosSpacing.sm, vertical = 2.dp),
                    )
                }

                PosAutoSizeText(
                    text = item.product.productNumber + "-" + item.product.productName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }

            // Extras (if any)
            item.extras?.takeIf { it.isNotEmpty() }?.let { extras ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(start = 32.dp),
                ) {
                    Text(
                        text = "+",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text =
                            extras.joinToString(", ") { it.name } + " " +
                                extras.fold(Decimal.ZERO) { acc, e -> acc + e.priceDelta.toDecimalOrZero() * Decimal.of(e.quantity) }.toFormattedCurrency(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Side notes (if any)
            item.sideNotes?.takeIf { it.isNotBlank() }?.let { notes ->
                Text(
                    text = "💬 $notes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(start = 32.dp),
                )
            }

            // Status badges (wraps on compact screens)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(start = 32.dp),
            ) {
                // Item Status Badge with different colors
                val (statusText, statusColor, statusTextColor) =
                    when (item.itemStatus.uppercase()) {
                        "OPEN" ->
                            Triple(
                                str(TableOrderHistoryKeys.statusOpen),
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.onPrimaryContainer,
                            )

                        "PAID" ->
                            Triple(
                                str(TableOrderHistoryKeys.statusPaid),
                                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.8f),
                                MaterialTheme.colorScheme.onTertiaryContainer,
                            )

                        "CANCELLED" ->
                            Triple(
                                str(TableOrderHistoryKeys.statusCancelled),
                                MaterialTheme.colorScheme.errorContainer,
                                MaterialTheme.colorScheme.onErrorContainer,
                            )

                        else ->
                            Triple(
                                item.itemStatus,
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                    }

                Surface(
                    color = statusColor,
                    shape = MaterialTheme.shapes.extraSmall,
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusTextColor,
                        modifier = Modifier.padding(horizontal = PosSpacing.sm, vertical = 2.dp),
                    )
                }

                if (item.takeAway) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.extraSmall,
                    ) {
                        Text(
                            text = str(TableOrderHistoryKeys.takeaway),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = PosSpacing.sm, vertical = 2.dp),
                        )
                    }
                }

                if (item.transferred) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.extraSmall,
                    ) {
                        Text(
                            text = str(TableOrderHistoryKeys.transferred),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = PosSpacing.sm, vertical = 2.dp),
                        )
                    }
                }

                if (item.customPrice) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.extraSmall,
                    ) {
                        Text(
                            text = str(TableOrderHistoryKeys.customPrice),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = PosSpacing.sm, vertical = 2.dp),
                        )
                    }
                }
            }
        }

        // Right side: Price
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = item.finalPrice.toDecimalOrZero().toFormattedCurrency(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            // Show discount if applied
            if (item.discount > 0 && item.discount < 1) {
                Text(
                    text = "-${item.discount.discountToPercentage()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
