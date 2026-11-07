package org.example.project.ui.screens.billing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.BillingKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.dashboard.presentation.state.OrderItemForSelection

/**
 * Billing item list showing order items with selection controls.
 *
 * Displays a header with "Order Items" title and "Select All" checkbox,
 * column headers for Article/Unit Price/Qty, a scrollable list of
 * [PosSelectableItemCard] items, and a footer summary.
 */
@Composable
fun PosBillingItemList(
    orderItems: List<OrderItemForSelection>,
    onItemQuantityChange: (List<Long>, Int) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
//    val selectedCount = orderItems.count { it.isSelected }
//    val selectedTotal = orderItems.filter { it.isSelected }.sumOf { it.totalPrice }
    val allSelected =
        orderItems.isNotEmpty() &&
            orderItems.all { it.selectedQuantity == it.maxQuantity }

    Column(modifier = modifier) {
        // Header row: title + select all
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = str(BillingKeys.orderOverviewTitle),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = str(BillingKeys.selectAllLabel),
                    style = MaterialTheme.typography.labelMedium,
                )
                Checkbox(
                    checked = allSelected,
                    onCheckedChange = { checked ->
                        if (checked) onSelectAll() else onClearSelection()
                    },
                )
            }
        }

        // Column headers
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = str(BillingKeys.articleHeader),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = str(BillingKeys.unitPriceHeader),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = str(BillingKeys.sumHeader),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()

        // Scrollable item list
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // The id list itself, not its hashCode(): List.hashCode() collides for disjoint
            // groups ([100, 133] and [101, 102] both hash to 4194), which is a duplicate
            // LazyColumn key and a crash.
            items(orderItems, key = { it.orderItemIds.joinToString(",") }) { item ->
                PosSelectableItemCard(
                    item = item,
                    onQuantityChange = { qty -> onItemQuantityChange(item.orderItemIds, qty) },
                )
            }
        }

        // Footer: selected count + total
//        HorizontalDivider()
//        Row(
//            modifier = Modifier
//                .fillMaxWidth()
//                .padding(12.dp),
//            horizontalArrangement = Arrangement.SpaceBetween,
//            verticalAlignment = Alignment.CenterVertically
//        ) {
//            Text(
//                text = str(BillingKeys.selectedItemsCount, selectedCount),
//                style = MaterialTheme.typography.bodyMedium,
//                fontWeight = FontWeight.Medium
//            )
//            Text(
//                text = selectedTotal.toFormattedCurrency(),
//                style = MaterialTheme.typography.titleMedium,
//                fontWeight = FontWeight.Bold,
//                color = MaterialTheme.colorScheme.primary
//            )
//        }
    }
}
