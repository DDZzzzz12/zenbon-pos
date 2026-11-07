package org.example.project.ui.screens.order

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.example.project.core.auth.SettingManager
import org.example.project.core.data.models.Items
import org.example.project.core.presentation.strings.keys.AccessibilityKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.core.utils.toSignedDeltaCurrency
import org.example.project.money.Decimal
import org.example.project.money.Percentage
import org.example.project.money.format
import org.example.project.ui.components.PosDangerIconButton
import org.example.project.ui.components.PosFilledIconButton
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosMarqueeText
import org.koin.compose.koinInject

@Composable
fun PosOrderItemRow(
    item: Items,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit,
    onRemove: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    settingManager: SettingManager = koinInject(),
) {
    val baseUrl by settingManager.url.collectAsState()
    val fullImageUrl = item.product.resolveImageUrl(baseUrl)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // Product image thumbnail
                if (fullImageUrl != null) {
                    AsyncImage(
                        model = fullImageUrl,
                        contentDescription = item.product.name,
                        modifier =
                            Modifier
                                .size(40.dp)
                                .clip(MaterialTheme.shapes.small),
                        contentScale = ContentScale.Crop,
                    )
                }
                PosMarqueeText(
                    text = item.product.productNumber,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Middle: name + extras + discount
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                PosMarqueeText(
                    text = item.product.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )

                // Chosen choice-group options, grouped by group name. Falls back to legacy
                // extras only when no selectedOptions exist (so already-placed legacy items
                // still render their extras).
                if (item.selectedOptions.isNotEmpty()) {
                    item.selectedOptions
                        .groupBy { it.groupName }
                        .forEach { (groupName, options) ->
                            if (groupName.isNotBlank()) {
                                Text(
                                    text = groupName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            options.forEach { option ->
                                val delta =
                                    if (!option.priceDelta.isZero) {
                                        " ${option.priceDelta.toSignedDeltaCurrency()}"
                                    } else {
                                        ""
                                    }
                                Text(
                                    text = "${option.name}$delta",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    fontStyle = FontStyle.Italic,
                                )
                            }
                        }
                } else if (!item.extras.isNullOrEmpty()) {
                    item.extras.forEach { extra ->
                        Text(
                            text = "+ ${extra.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                            fontStyle = FontStyle.Italic,
                        )
                    }
                }

                if (!item.sideNotes.isNullOrBlank()) {
                    Text(
                        text = item.sideNotes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        fontStyle = FontStyle.Italic,
                    )
                }

                // Discount badge. item.discount is the multiplier, so anything below ONE is a
                // discount and its complement is the percentage off — computed exactly.
                if (item.discount < Decimal.ONE) {
                    val discountPercent = Percentage.ofFraction(Decimal.ONE - item.discount)
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = "-${discountPercent.format()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Right: price + controls
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Price
                Text(
                    text = item.totalPrice.toFormattedCurrency(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )

                // Quantity controls + action buttons
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // Decrease
                    PosFilledIconButton(
                        onClick = onDecrease,
                        icon = Icons.Default.Remove,
                        contentDescription = str(AccessibilityKeys.decrease),
                    )

                    // Quantity badge
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = "${item.quantity}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }

                    // Increase
                    PosFilledIconButton(
                        onClick = onIncrease,
                        icon = Icons.Default.Add,
                        contentDescription = str(AccessibilityKeys.increase),
                    )

                    // Edit
                    PosIconButton(
                        onClick = onEdit,
                        icon = Icons.Default.Edit,
                        contentDescription = str(CommonKeys.edit),
                    )

                    // Delete
                    PosDangerIconButton(
                        onClick = onRemove,
                        icon = Icons.Default.Delete,
                        contentDescription = str(CommonKeys.delete),
                    )
                }
            }
        }
    }
}
