package org.example.project.ui.screens.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.TableDetailKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosOrderItemCard

data class OrderItemDisplay(
    val quantity: Int,
    val name: String,
    val extras: String = "",
    val price: String,
)

@Composable
fun PosTableOrderList(
    items: List<OrderItemDisplay> = emptyList(),
    totalAmount: String = "",
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = str(TableDetailKeys.currentOrder),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (totalAmount.isNotEmpty()) {
                Text(
                    text = totalAmount,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (items.isEmpty()) {
            Text(
                text = str(TableDetailKeys.noItems),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
            )
        } else {
            LazyColumn {
                items(items) { item ->
                    PosOrderItemCard(
                        quantity = item.quantity,
                        productName = item.name,
                        price = item.price,
                        extras = item.extras.ifBlank { null },
                    )
                }
            }
        }
    }
}
