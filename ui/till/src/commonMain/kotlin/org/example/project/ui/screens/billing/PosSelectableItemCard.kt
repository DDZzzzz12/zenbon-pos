package org.example.project.ui.screens.billing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.BillingKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.features.dashboard.presentation.state.OrderItemForSelection
import org.example.project.features.dashboard.presentation.viewmodel.BillingMoneyMath
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosFilledIconButton
import org.example.project.ui.components.PosMarqueeText

@Composable
fun PosSelectableItemCard(
    item: OrderItemForSelection,
    onQuantityChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSelected = item.isSelected

    PosCard(
        modifier = modifier,
        onClick = {
            if (isSelected) onQuantityChange(0) else onQuantityChange(item.maxQuantity)
        },
        selected = isSelected,
        variant = PosCardVariant.Plain,
    ) {
        body {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    PosMarqueeText(
                        text = "${item.item.product.productNumber}-${item.item.product.productName}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    item.item.extras?.takeIf { it.isNotEmpty() }?.let { extras ->
                        PosMarqueeText(
                            text = "+ ${extras.joinToString(", ") { it.name }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item.item.sideNotes?.takeIf { it.isNotBlank() }?.let { note ->
                        PosMarqueeText(
                            text = note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }

                if (item.maxQuantity > 1) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        PosFilledIconButton(
                            icon = Icons.Default.Remove,
                            contentDescription = str(BillingKeys.decreaseAccessibility),
                            onClick = {
                                val newQty = (item.selectedQuantity - 1).coerceAtLeast(0)
                                onQuantityChange(newQty)
                            },
                            enabled = item.selectedQuantity > 0,
                            modifier = Modifier.size(28.dp),
                            iconSize = 16.dp,
                            colors =
                                IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                ),
                        )

                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color =
                                if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                        ) {
                            Text(
                                text = "${item.selectedQuantity}/${item.maxQuantity}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color =
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }

                        PosFilledIconButton(
                            icon = Icons.Default.Add,
                            contentDescription = str(BillingKeys.increaseAccessibility),
                            onClick = {
                                val newQty = (item.selectedQuantity + 1).coerceAtMost(item.maxQuantity)
                                onQuantityChange(newQty)
                            },
                            enabled = item.selectedQuantity < item.maxQuantity,
                            modifier = Modifier.size(28.dp),
                            iconSize = 16.dp,
                            colors =
                                IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                        )
                    }
                }

                // Unit price and line sum under the "Einzelpreis" / "Summe" headers, on every row
                // (E2E-2026-09-18 #12). Unselected rows show what the whole line costs.
                Text(
                    text = item.unitPrice.toFormattedCurrency(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text =
                        if (isSelected) {
                            item.totalPrice.toFormattedCurrency()
                        } else {
                            BillingMoneyMath.selectedLineTotal(item.unitPrice, item.maxQuantity).toFormattedCurrency()
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
