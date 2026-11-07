package org.example.project.admin.features.zBon.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.order.ItemTransferHistoryDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.formatShortDateTime
import org.example.project.ui.components.formatMoneyForDisplay

@Composable
fun TransferDetailPanel(
    transfer: ItemTransferHistoryDto.ItemTransferHistoryResponseDto,
    currencyCode: String,
    modifier: Modifier = Modifier,
) {
    val language = LocalAppLanguage.current
    val isIncoming = transfer.direction == "IN"

    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            text = "${str(AdminStringKeys.DetailTransferTitle)} #${transfer.transferId}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(12.dp))

        // Direction
        SectionLabel(str(AdminStringKeys.DetailDirection))
        Text(
            text = if (isIncoming) str(AdminStringKeys.DetailDirectionIn) else str(AdminStringKeys.DetailDirectionOut),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )

        Spacer(Modifier.height(4.dp))
        SectionLabel("${str(AdminStringKeys.DetailInitiatedAt)} / ${str(AdminStringKeys.DetailInitiatedBy)}")
        Text(
            text = "${transfer.transferTime.formatShortDateTime(language)} — ${transfer.employeeName}",
            style = MaterialTheme.typography.bodyMedium,
        )

        // From → To
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = transfer.fromTableNumber,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = transfer.toTableNumber,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(Modifier.height(4.dp))
        SectionLabel(str(CommonKeys.total))
        Text(formatMoneyForDisplay(transfer.totalAmount, currencyCode), style = MaterialTheme.typography.bodyMedium)

        // Transferred Items
        if (transfer.items.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${str(AdminStringKeys.DetailTransferredItems)} (${transfer.items.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))

            transfer.items.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${item.quantity}× ${item.product.productName}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = formatMoneyForDisplay(item.finalPrice, currencyCode),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                item.extras?.let { extras ->
                    if (extras.isNotEmpty()) {
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

                Text(
                    text = "${str(CommonKeys.status)}: ${item.itemStatus}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
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
