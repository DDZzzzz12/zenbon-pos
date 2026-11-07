package org.example.project.features.dashboard.presentation.components.customer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.customer.CustomerAddressDto
import org.example.project.core.domain.dto.customer.CustomerDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.keys.CustomerAddressKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant

/**
 * Address selection step content.
 */
@Composable
fun AddressSelectionContent(
    customer: CustomerDto.CustomerResponseDto,
    addresses: List<CustomerAddressDto.CustomerAddressResponseDto>,
    isTakeawayTable: Boolean,
    isDeliverySession: Boolean,
    onAddressSelect: (CustomerAddressDto.CustomerAddressResponseDto) -> Unit,
    onToggleDeliverySession: (Boolean) -> Unit,
    currentLanguage: Language,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Customer info
        PosCard(
            variant = PosCardVariant.Plain,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ) {
            body {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Column {
                        Text(
                            text = customer.fullName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        if (customer.phone != null) {
                            Text(
                                text = customer.phone!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
        }

        // Delivery session toggle (only for takeaway tables)
        if (isTakeawayTable) {
            PosCard(
                variant = PosCardVariant.Plain,
                containerColor =
                    if (isDeliverySession) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
            ) {
                body {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeliveryDining,
                                contentDescription = null,
                                tint =
                                    if (isDeliverySession) {
                                        MaterialTheme.colorScheme.onTertiaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                            Column {
                                Text(
                                    text = str(CustomerAddressKeys.deliverySession),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text =
                                        if (isDeliverySession) {
                                            str(CustomerAddressKeys.orderWillBeDelivered)
                                        } else {
                                            str(CustomerAddressKeys.takeawayOrder)
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Switch(
                            checked = isDeliverySession,
                            onCheckedChange = onToggleDeliverySession,
                        )
                    }
                }
            }
        }

        // Addresses header
        Text(
            text = str(CustomerAddressKeys.selectDeliveryAddress),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        // Address list
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(addresses, key = { it.id }) { address ->
                AddressCard(
                    address = address,
                    onClick = { onAddressSelect(address) },
                    currentLanguage = currentLanguage,
                )
            }
        }
    }
}
