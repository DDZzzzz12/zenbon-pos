/**
 * Selectable order item card component with checkbox for multi-select operations.
 *
 * Displays order item details with checkbox selection for bulk cancel/transfer operations.
 * Supports both compact and expanded layouts based on screen size.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.dashboard.presentation.components.order

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.keys.AccessibilityKeys
import org.example.project.core.presentation.strings.keys.TableDetailKeys
import org.example.project.core.presentation.strings.keys.TableOrderKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.toDecimalOrZero
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant

/**
 * Order item card with checkbox selection.
 *
 * Displays product information, quantity, price, and selection checkbox
 * for multi-select operations like cancel or transfer.
 *
 * @param orderItem The order item to display
 * @param selectedQuantity Whether this item is currently selected
 * @param currentLanguage Current app language for localization
 * @param onSelectionToggle Callback to toggle selection state: true for select, false for deselect
 * @param modifier Modifier for styling
 * @param compactMode Whether to use compact layout
 */
@Composable
fun SelectableOrderItemCard(
    orderItem: OrderItemDto.OrderItemResponseDto,
    selectedQuantity: Int,
    currentLanguage: Language,
    onSelectionToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    compactMode: Boolean = false,
) {
    val product = orderItem.product

    PosCard(
        modifier = modifier,
        variant = PosCardVariant.Plain,
        selected = selectedQuantity > 0,
        onClick = { onSelectionToggle(true) },
    ) {
        body {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Item details (left side)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(if (compactMode) 2.dp else 4.dp),
                ) {
                    if (!compactMode) {
                        Text(
                            text = product.productNumber,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Text(
                        text = if (compactMode) product.productNumber + " " + product.productName else product.productName,
                        style =
                            if (compactMode) {
                                MaterialTheme.typography.bodyMedium
                            } else {
                                MaterialTheme.typography.bodyLarge
                            },
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    // Special instructions if any
                    val sideNotes = orderItem.sideNotes
                    if (!sideNotes.isNullOrBlank()) {
                        Text(
                            text = sideNotes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                            fontStyle = FontStyle.Italic,
                            maxLines = if (compactMode) 1 else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // Extras if any
                    val extras = orderItem.extras
                    if (!extras.isNullOrEmpty() && !compactMode) {
                        extras.take(2).forEach { extra ->
                            Text(
                                text = "+ ${extra.name}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                                fontStyle = FontStyle.Italic,
                            )
                        }
                        if (extras.size > 2) {
                            Text(
                                text = "+ ${extras.size - 2} more...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontStyle = FontStyle.Italic,
                            )
                        }
                    }

                    // Unit price — parse the DTO string exactly and format via :platform-money (no truncating cents).
                    Text(
                        text = "${orderItem.unitPrice.toDecimalOrZero().toFormattedCurrency()} ${str(TableOrderKeys.each)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.width(if (compactMode) 8.dp else 12.dp))

                // Quantity and price (right side)
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(if (compactMode) 4.dp else 8.dp),
                ) {
                    // Show selected quantity indicator if item is selected
                    if (selectedQuantity > 0) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).clickable { onSelectionToggle(false) },
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = str(AccessibilityKeys.deselect),
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier =
                                        Modifier
                                            .size(16.dp)
                                            .clickable { onSelectionToggle(false) },
                                )
                                Text(
                                    text = str(TableDetailKeys.selected, selectedQuantity),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            }
                        }
                    }

                    // Quantity badge
                    Surface(
                        color =
                            if (selectedQuantity > 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = "×${orderItem.quantity}",
                            style =
                                if (compactMode) {
                                    MaterialTheme.typography.labelLarge
                                } else {
                                    MaterialTheme.typography.titleMedium
                                },
                            fontWeight = FontWeight.Bold,
                            color =
                                if (selectedQuantity > 0) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                },
                            modifier =
                                Modifier.padding(
                                    horizontal = if (compactMode) 8.dp else 12.dp,
                                    vertical = if (compactMode) 2.dp else 4.dp,
                                ),
                        )
                    }

                    // Item subtotal — parse the DTO string exactly and format via :platform-money.
                    Text(
                        text = orderItem.finalPrice.toDecimalOrZero().toFormattedCurrency(),
                        style =
                            if (compactMode) {
                                MaterialTheme.typography.titleMedium
                            } else {
                                MaterialTheme.typography.titleLarge
                            },
                        fontWeight = FontWeight.Bold,
                        color =
                            if (selectedQuantity > 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                    )
                }
            }
        } // body
    }
}
