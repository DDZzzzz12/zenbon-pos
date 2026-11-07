package org.example.project.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.ui.theme.PosSpacing
import org.example.project.ui.theme.PosTheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PosOrderItemCard(
    quantity: Int,
    productName: String,
    price: String,
    modifier: Modifier = Modifier,
    extras: String? = null,
    sideNotes: String? = null,
    unitPrice: String? = null,
    discount: String? = null,
    badges: List<String> = emptyList(),
    status: OrderItemStatus? = null,
    isSelected: Boolean = false,
    isCancelled: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    val hasDetails =
        !extras.isNullOrBlank() ||
            !sideNotes.isNullOrBlank() ||
            discount != null ||
            badges.isNotEmpty()
    var expanded by remember { mutableStateOf(false) }

    PosCard(
        modifier = modifier,
        onClick = onClick,
        selected = isSelected,
        variant = PosCardVariant.Plain,
        containerColor = if (isCancelled) PosTheme.colors.posError.copy(alpha = 0.05f) else null,
    ) {
        body {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = productName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (isCancelled) TextDecoration.LineThrough else null,
                        modifier = Modifier.weight(1f),
                    )

                    if (unitPrice != null) {
                        Text(
                            text = unitPrice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (hasDetails) {
                        // raw Material 3 by design: this IS a Pos* template
                        IconButton(
                            onClick = { expanded = !expanded },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint =
                                    if (expanded) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "${quantity}x",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color =
                            if (isCancelled) {
                                PosTheme.colors.posError
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                    )

                    if (trailingContent != null) {
                        trailingContent()
                    } else if (status != null) {
                        OrderItemStatusChip(status = status.toOrderItemCardStatus())
                    }

                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Text(
                            text = price.toFormattedCurrency(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color =
                                if (isCancelled) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            textDecoration = if (isCancelled) TextDecoration.LineThrough else null,
                        )
                    }
                }

                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    Column(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (!extras.isNullOrBlank()) {
                            Text(
                                text = "+ $extras",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (!sideNotes.isNullOrBlank()) {
                            Text(
                                text = sideNotes,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (discount != null) {
                            Text(
                                text = discount,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (badges.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                badges.forEach { badge ->
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                    ) {
                                        Text(
                                            text = badge,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = PosSpacing.sm, vertical = 1.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

enum class OrderItemStatus {
    PAID,
    OPEN,
    CANCELLED,
}

fun OrderItemStatus.toOrderItemCardStatus(): OrderItemCardStatus =
    when (this) {
        OrderItemStatus.PAID -> OrderItemCardStatus.PAID
        OrderItemStatus.OPEN -> OrderItemCardStatus.OPEN
        OrderItemStatus.CANCELLED -> OrderItemCardStatus.CANCELLED
    }
