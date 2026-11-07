package org.example.project.ui.screens.billing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.BillingKeys
import org.example.project.core.presentation.strings.keys.PromotionKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.features.dashboard.presentation.state.AppliedPromotion
import org.example.project.features.dashboard.presentation.state.SelectedItemSummary
import org.example.project.features.dashboard.presentation.state.TaxBreakdown
import org.example.project.money.Decimal
import org.example.project.ui.components.PosAutoSizeText
import org.example.project.ui.components.PosMarqueeText

/**
 * Review panel showing selected items summary, tax breakdown, and totals.
 *
 * Displays a header with item count and optional SPLIT badge,
 * a list of selected items with quantities and prices,
 * a tax breakdown card, and the selection total.
 */
@Composable
fun PosBillingReviewPanel(
    modifier: Modifier = Modifier,
    selectedItems: List<SelectedItemSummary>,
    articleCount: Int,
    taxBreakdown: List<TaxBreakdown>,
    subtotal: Decimal,
    taxTotal: Decimal,
    billTotal: Decimal,
    isSplit: Boolean = false,
    appliedPromotions: List<AppliedPromotion> = emptyList(),
    promotionTotal: Decimal = Decimal.ZERO,
    onRemovePromotion: ((Long) -> Unit)? = null,
) {
    Column(modifier = modifier) {
        // Header: title + item count + SPLIT badge
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = str(BillingKeys.currentSelectionTitle),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = str(BillingKeys.itemsCountLabel, articleCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (isSplit) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = str(BillingKeys.splitBadge),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }

        HorizontalDivider()

        // Selected items list
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (selectedItems.isEmpty()) {
                item {
                    Text(
                        text = str(BillingKeys.noItemsSelected),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                items(selectedItems) { item ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            PosMarqueeText(
                                text = "${item.selectedQuantity}\u00D7 ${item.productName}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            if (item.extras.isNotEmpty()) {
                                PosMarqueeText(
                                    text = "+ ${item.extras.joinToString(", ")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            item.sideNotes?.takeIf { it.isNotBlank() }?.let { note ->
                                PosMarqueeText(
                                    text = note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                        Text(
                            text = item.totalPrice.toFormattedCurrency(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        // Tax breakdown card — always visible at bottom
//        if (taxBreakdown.isNotEmpty()) {
//            Spacer(modifier = Modifier.height(8.dp))
//            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
//            Spacer(modifier = Modifier.height(8.dp))
//
//            Surface(
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .padding(horizontal = 12.dp),
//                shape = RoundedCornerShape(8.dp),
//                color = MaterialTheme.colorScheme.surface,
//                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
//            ) {
//                Column(
//                    modifier = Modifier.padding(12.dp),
//                    verticalArrangement = Arrangement.spacedBy(6.dp)
//                ) {
//                    taxBreakdown.forEach { tax ->
//                        Row(
//                            modifier = Modifier.fillMaxWidth(),
//                            horizontalArrangement = Arrangement.SpaceBetween
//                        ) {
//                            Text(
//                                text = str(BillingKeys.taxLabel, tax.vatRate.toInt()) +
//                                    " (${tax.taxableAmount.toFormattedCurrency()})",
//                                style = MaterialTheme.typography.bodySmall,
//                                color = MaterialTheme.colorScheme.onSurfaceVariant
//                            )
//                            Text(
//                                text = tax.taxAmount.toFormattedCurrency(),
//                                style = MaterialTheme.typography.bodySmall
//                            )
//                        }
//                    }
//                }
//            }
//        }

        // Promotion deductions section
        if (appliedPromotions.isNotEmpty()) {
            HorizontalDivider()
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = str(PromotionKeys.promotionSection),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
                appliedPromotions.forEach { promo ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                        ) {
                            PosAutoSizeText(
                                text = promo.promotionName,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (onRemovePromotion != null) {
                                // raw Material 3 by design: non-48dp size(24.dp) modifier not supported by PosIconButton
                                IconButton(
                                    onClick = { onRemovePromotion(promo.promotionId) },
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        Text(
                            text = "-${promo.deductedAmount.toFormattedCurrency()}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        // Bottom: Selection total
        HorizontalDivider()
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = str(BillingKeys.totalLabel),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Column(horizontalAlignment = Alignment.End) {
                if (promotionTotal > Decimal.ZERO) {
                    Text(
                        text = billTotal.toFormattedCurrency(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = (billTotal - promotionTotal).coerceAtLeast(Decimal.ZERO).toFormattedCurrency(),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
