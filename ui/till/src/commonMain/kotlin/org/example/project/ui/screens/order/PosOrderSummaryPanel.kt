package org.example.project.ui.screens.order

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.OrderSummaryKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.money.Decimal

@Composable
fun PosOrderSummaryPanel(
    discount: Decimal,
    total: Decimal,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Discount
            if (discount > Decimal.ZERO) {
                SummaryRow(
                    label = str(OrderSummaryKeys.discount),
                    value = discount,
                    emphasized = false,
                    isNegative = true,
                )
            }

            // Divider before total
            if (discount > Decimal.ZERO) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    thickness = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Total
            SummaryRow(
                label = str(OrderSummaryKeys.total),
                value = total,
                emphasized = true,
            )
        }
    }
}

@Composable
private fun SummaryRow(
    label: String,
    value: Decimal,
    emphasized: Boolean,
    isNegative: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = if (emphasized) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal,
        )

        Text(
            text = if (isNegative) "-${value.toFormattedCurrency()}" else value.toFormattedCurrency(),
            style = if (emphasized) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal,
            color =
                when {
                    isNegative -> MaterialTheme.colorScheme.error
                    emphasized -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}
