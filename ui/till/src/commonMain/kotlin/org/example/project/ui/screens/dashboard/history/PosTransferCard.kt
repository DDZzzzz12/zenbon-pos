package org.example.project.ui.screens.dashboard.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import org.example.project.core.domain.dto.order.ItemTransferHistoryDto
import org.example.project.core.presentation.strings.keys.TableOrderHistoryKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.formatReadableDateTime
import org.example.project.core.utils.rememberLanguage
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.ui.components.OrderItemStatus
import org.example.project.ui.components.PosAutoSizeText
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosOrderItemCard
import org.example.project.ui.theme.PosSpacing
import org.example.project.ui.theme.PosTheme

/**
 * Card showing a transfer entry in the order history timeline.
 *
 * Two visual modes:
 * - Incoming (direction="IN"): Blue accent, ↓ arrow, "Transfer from Table X", "TRANSFERRED IN" chip
 * - Outgoing (direction="OUT"): Gray accent, ↑ arrow, "Transfer to Table X", "TRANSFERRED OUT" chip
 *
 * @param transfer The transfer data to display
 * @param isSelected Whether this card is currently selected
 * @param onClick Callback when the card is tapped
 * @param expanded Whether the item list is visible (default true)
 * @param onExpandToggle Callback when the expand/collapse icon is tapped; if null, no toggle icon is shown
 * @param modifier Modifier for the root element
 */
@Composable
fun PosTransferCard(
    transfer: ItemTransferHistoryDto.ItemTransferHistoryResponseDto,
    isSelected: Boolean = false,
    onClick: () -> Unit = {},
    expanded: Boolean = true,
    onExpandToggle: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val language by rememberLanguage()
    val isIncoming = transfer.direction.uppercase() == "IN"

    val colorScheme = MaterialTheme.colorScheme
    val accentColor = if (isIncoming) PosTheme.colors.posReserved else colorScheme.outline
    val chipBg = if (isIncoming) colorScheme.primaryContainer else colorScheme.surfaceContainerHighest
    val chipText = if (isIncoming) colorScheme.onPrimaryContainer else colorScheme.onSurfaceVariant
    val chipLabel = if (isIncoming) str(TableOrderHistoryKeys.transferredIn) else str(TableOrderHistoryKeys.transferredOut)
    val titleText =
        if (isIncoming) {
            str(TableOrderHistoryKeys.transferFromTable, transfer.fromTableNumber)
        } else {
            str(TableOrderHistoryKeys.transferToTable, transfer.toTableNumber)
        }
    val arrowSymbol = if (isIncoming) "↓" else "↑"

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
    val contentAlpha = if (isIncoming) 1f else 0.75f

    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(borderWidth, borderColor),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
        ) {
            // Left accent bar
            Box(
                modifier =
                    Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary else accentColor,
                        ),
            )

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Header row: arrow + title + total amount + expand toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = arrowSymbol,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = accentColor.copy(alpha = contentAlpha),
                        modifier = Modifier.padding(end = PosSpacing.sm),
                    )
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                    )
                    Text(
                        text = transfer.totalAmount.toFormattedCurrency(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = accentColor.copy(alpha = contentAlpha),
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

                // Subtitle: timestamp + transferred by employee
                PosAutoSizeText(
                    text = "${transfer.transferTime.formatReadableDateTime(
                        language,
                    )} | ${str(TableOrderHistoryKeys.transferredBy, transfer.employeeName)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                )

                // Status chip
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = chipBg,
                ) {
                    Text(
                        text = chipLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = chipText,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }

                // Item list (collapsible)
                AnimatedVisibility(
                    visible = expanded && transfer.items.isNotEmpty(),
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

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
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // Footer: item count + total
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = str(TableOrderHistoryKeys.transferSummary, transfer.items.size.toString(), transfer.totalAmount),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}
