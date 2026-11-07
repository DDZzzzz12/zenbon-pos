package org.example.project.admin.features.zBon.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.order.OrderItemDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.formatShortDateTime
import org.example.project.ui.components.formatMoneyForDisplay

@Composable
fun CancellationDetailPanel(
    cancellation: OrderItemDto.CancellationResponseDto,
    currencyCode: String,
    modifier: Modifier = Modifier,
) {
    val language = LocalAppLanguage.current
    val item = cancellation.orderItemId

    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            text = "${str(AdminStringKeys.DetailCancellationTitle)} #${cancellation.cancellationId}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(12.dp))

        // Cancellation info
        SectionLabel(str(AdminStringKeys.DetailCancelledAt))
        Text(cancellation.createdAt.formatShortDateTime(language), style = MaterialTheme.typography.bodyMedium)

        Spacer(Modifier.height(4.dp))
        SectionLabel(str(AdminStringKeys.DetailCancelledBy))
        Text(cancellation.employeeNumber, style = MaterialTheme.typography.bodyMedium)

        cancellation.reason?.let { reason ->
            Spacer(Modifier.height(4.dp))
            SectionLabel(str(CommonKeys.notes))
            Text(reason, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(4.dp))
        SectionLabel(str(AdminStringKeys.FloorPlanTableNumber))
        Text(cancellation.tableNumber, style = MaterialTheme.typography.bodyMedium)

        // Item detail section
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        Text(
            text = str(AdminStringKeys.DetailOrderTitle),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))

        item.product.let { product ->
            SectionLabel(str(AdminStringKeys.ProductColName))
            Text(product.productName, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(4.dp))
        SectionLabel(str(CommonKeys.quantity))
        Text(item.quantity.toString(), style = MaterialTheme.typography.bodyMedium)

        Spacer(Modifier.height(4.dp))
        SectionLabel(str(CommonKeys.amount))
        Text(formatMoneyForDisplay(cancellation.canceledAmount, currencyCode), style = MaterialTheme.typography.bodyMedium)

        Spacer(Modifier.height(4.dp))
        SectionLabel(str(CommonKeys.status))
        Text(item.itemStatus, style = MaterialTheme.typography.bodyMedium)

        // Modifiers
        item.extras?.let { extras ->
            if (extras.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
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

        // TSE Data section
        // Note: CancellationResponseDto doesn't carry explicit TSE fields,
        // but the order items themselves may have TSE data from their original orders.
        // This section is reserved for when TSE data is available.
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
