/**
 * Card component to display selected customer and delivery address information.
 *
 * Shows customer details, delivery address, and delivery/takeaway status.
 * Provides option to change the selection.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.dashboard.presentation.components.customer

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.customer.CustomerAddressDto
import org.example.project.core.domain.dto.customer.CustomerDto
import org.example.project.core.presentation.strings.keys.CustomerAddressKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosSecondaryButton

/**
 * Card displaying selected customer and address information.
 *
 * @param customer Selected customer data
 * @param address Selected delivery address
 * @param isDeliverySession Whether this is a delivery session
 * @param modifier Modifier for styling
 */
@Composable
fun SelectedCustomerAddressCard(
    customer: CustomerDto.CustomerResponseDto,
    address: CustomerAddressDto.CustomerAddressResponseDto,
    isDeliverySession: Boolean,
    onBackAddressSelection: () -> Unit,
    onClearSelectedCustomer: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PosCard(
            modifier = modifier,
            variant = PosCardVariant.Tonal,
            containerColor =
                if (isDeliverySession) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
        ) {
            body {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Header with delivery status and change button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector =
                                    if (isDeliverySession) {
                                        Icons.Default.DeliveryDining
                                    } else {
                                        Icons.Default.ShoppingBag
                                    },
                                contentDescription = null,
                                tint =
                                    if (isDeliverySession) {
                                        MaterialTheme.colorScheme.onTertiaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    },
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = if (isDeliverySession) "Delivery" else "Takeaway",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color =
                                    if (isDeliverySession) {
                                        MaterialTheme.colorScheme.onTertiaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    },
                            )
                        }
                    }

                    HorizontalDivider(
                        color =
                            if (isDeliverySession) {
                                MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.2f)
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                            },
                    )

                    // Customer information
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint =
                                if (isDeliverySession) {
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                },
                        )
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = customer.fullName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color =
                                    if (isDeliverySession) {
                                        MaterialTheme.colorScheme.onTertiaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    },
                            )
                            if (customer.phone != null) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Phone,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint =
                                            if (isDeliverySession) {
                                                MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                                            } else {
                                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                            },
                                    )
                                    Text(
                                        text = customer.phone!!,
                                        style = MaterialTheme.typography.bodySmall,
                                        color =
                                            if (isDeliverySession) {
                                                MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                                            } else {
                                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                            },
                                    )
                                }
                            }
                        }
                    }

                    // Delivery address information
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = address.formattedAddress,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            if (address.additionalInfo != null) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                                    )
                                    Text(
                                        text = address.additionalInfo!!,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                    )
                                }
                            }
                        }
                    }
                }
            } // body
        }

        Spacer(modifier = Modifier.weight(1f))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Change address button
            PosSecondaryButton(
                text = str(CustomerAddressKeys.changeAddress),
                onClick = { onBackAddressSelection() },
                modifier = Modifier.weight(1f),
            )

            // Change customer button
            PosSecondaryButton(
                text = str(CustomerAddressKeys.changeCustomer),
                onClick = { onClearSelectedCustomer() },
                modifier = Modifier.weight(1f),
            )
        }

        // Confirm button
        PosButton(
            text = str(CustomerAddressKeys.confirmAndContinue),
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
