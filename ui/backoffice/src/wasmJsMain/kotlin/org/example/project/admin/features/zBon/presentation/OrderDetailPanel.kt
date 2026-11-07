package org.example.project.admin.features.zBon.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.BillReceiptKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.enumStrings.getName
import org.example.project.core.utils.formatShortDateTime
import org.example.project.ui.components.TseQrCodeSection
import org.example.project.ui.components.formatMoneyForDisplay

@Composable
fun OrderDetailPanel(
    order: OrderDto.OrderResponseDto,
    currencyCode: String,
    modifier: Modifier = Modifier,
) {
    val language = LocalAppLanguage.current
    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            text = "${str(AdminStringKeys.DetailOrderTitle)} #${order.orderNumber}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(12.dp))

        // Order info section
        SectionLabel(str(CommonKeys.time))
        Text(order.orderTime.formatShortDateTime(language), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        SectionLabel(str(AdminStringKeys.FloorPlanTableNumber))
        Text(order.tableNumber, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        SectionLabel(str(CommonKeys.total))
        Text(formatMoneyForDisplay(order.orderTotal, currencyCode), style = MaterialTheme.typography.bodyMedium)

        TseQrCodeSection(tseQrCodeContent = order.tseTransaction)

        // Order Items section
        if (order.orderItems.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${str(BillReceiptKeys.itemsLabel)} (${order.orderItems.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))

            order.orderItems.forEach { item ->
                OrderItemRow(item, currencyCode)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun OrderItemRow(
    item: OrderItemDto.OrderItemResponseDto,
    currencyCode: String,
) {
    val language = LocalAppLanguage.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${item.quantity}× ${item.product.productName}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatMoneyForDisplay(item.finalPrice, currencyCode),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        item.sideNotes?.let { notes ->
            Text(
                text = notes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Modifiers (choice groups with price deltas)
        item.extras?.let { extras ->
            if (extras.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = str(AdminStringKeys.DetailModifiers),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                extras.forEach { extra ->
                    Text(
                        text = "  + ${extra.name} ${if (extra.priceDelta != "0.00") {
                            formatMoneyForDisplay(
                                extra.priceDelta,
                                currencyCode,
                            )
                        } else {
                            ""
                        }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row {
            Text(
                text = "${str(CommonKeys.status)}: ${ItemStatus.entries.firstOrNull { it.name == item.itemStatus }?.getName(language) ?: item.itemStatus}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.unitPrice != item.finalPrice) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "@ ${formatMoneyForDisplay(item.unitPrice, currencyCode)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
