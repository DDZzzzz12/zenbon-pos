package org.example.project.features.dashboard.presentation.components.order

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.example.project.core.data.models.Items
import org.example.project.core.presentation.strings.keys.AccessibilityKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.ui.components.*
import org.example.project.ui.components.PosMarqueeText

@Composable
fun OrderItemCard(
    modifier: Modifier = Modifier,
    orderItem: Items,
    onIncreaseQuantity: () -> Unit,
    onDecreaseQuantity: () -> Unit,
    onRemove: () -> Unit,
    onEdit: (() -> Unit)? = null,
) {
    val product = orderItem.product
    val posCardStatus =
        orderItem.itemStatus
            ?.uppercase()
            ?.let { s -> OrderItemCardStatus.entries.firstOrNull { it.name == s } }
            ?.toPosCardStatus()

    PosCard(
        modifier = modifier,
        variant = PosCardVariant.Tonal,
        status = posCardStatus,
    ) {
        body {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                product.imageUrl?.let { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = product.name,
                        modifier =
                            Modifier
                                .size(48.dp)
                                .clip(MaterialTheme.shapes.small),
                        contentScale = ContentScale.Crop,
                    )
                }
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = if (product.imageUrl != null) 8.dp else 0.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Row 1: ProductNumber + ProductName | Price
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PosMarqueeText(
                            text = "${product.productNumber} ${product.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = orderItem.totalPrice.toFormattedCurrency(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    // Extras
                    if (!orderItem.extras.isNullOrEmpty()) {
                        orderItem.extras.forEach { extra ->
                            Text(
                                text = "+ ${extra.name}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                                fontStyle = FontStyle.Italic,
                            )
                        }
                    }

                    // Side notes
                    if (!orderItem.sideNotes.isNullOrBlank()) {
                        Text(
                            text = orderItem.sideNotes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                            fontStyle = FontStyle.Italic,
                        )
                    }

                    // Row 2: Quantity controls | Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Quantity controls
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            IconButton( // raw Material 3 by design: non-square custom size (28×36dp) not expressible via PosIconButton.size
                                onClick = onDecreaseQuantity,
                                modifier = Modifier.height(28.dp).width(36.dp),
                            ) {
                                Icon(
                                    Icons.Default.Remove,
                                    contentDescription = str(AccessibilityKeys.decrease),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text(
                                    text = "${orderItem.quantity}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                            IconButton( // raw Material 3 by design: non-square custom size (28×36dp) not expressible via PosIconButton.size
                                onClick = onIncreaseQuantity,
                                modifier = Modifier.height(28.dp).width(36.dp),
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = str(AccessibilityKeys.increase),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }

                        // Action buttons
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (onEdit != null) {
                                IconButton( // raw Material 3 by design: non-square custom size (28×36dp) not expressible via PosIconButton.size
                                    onClick = onEdit,
                                    modifier = Modifier.height(28.dp).width(36.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = str(CommonKeys.edit),
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                            IconButton( // raw Material 3 by design: non-square custom size (28×36dp) not expressible via PosIconButton.size
                                onClick = onRemove,
                                modifier = Modifier.height(28.dp).width(36.dp),
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = str(CommonKeys.remove),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
