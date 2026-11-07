package org.example.project.features.dashboard.presentation.components.customer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.customer.CustomerAddressDto
import org.example.project.core.domain.dto.customer.CustomerDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.components.customer.CustomerSearchField
import org.example.project.core.presentation.strings.keys.CustomerAddressKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.dashboard.presentation.state.CustomerPanelEvent
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosDangerButton
import org.example.project.ui.components.PosTextButton

/**
 * Unified customer panel content. When no customer is selected, shows a search field.
 * When a customer is selected, shows review with info, delivery toggle, addresses, and actions.
 */
@Composable
fun CustomerReviewContent(
    customer: CustomerDto.CustomerResponseDto?,
    addresses: List<CustomerAddressDto.CustomerAddressResponseDto>,
    selectedAddress: CustomerAddressDto.CustomerAddressResponseDto?,
    isDeliverySession: Boolean,
    isTakeawayTable: Boolean,
    error: String?,
    onEvent: (CustomerPanelEvent) -> Unit,
    currentLanguage: Language,
    modifier: Modifier = Modifier,
) {
    // Step 1: No customer selected — show search
    if (customer == null) {
        CustomerSearchStep(
            error = error,
            onEvent = onEvent,
            modifier = modifier,
        )
        return
    }

    // Step 2: Customer selected — show review & confirm
    val phonePresent = !customer.phone.isNullOrBlank()
    val addressSelected = selectedAddress != null
    val confirmEnabled =
        if (isDeliverySession) {
            phonePresent && addressSelected
        } else {
            true
        }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. Customer Info Card
        PosCard(
            variant = PosCardVariant.Plain,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            body {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = customer.fullName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                text = str(CustomerAddressKeys.customerNumberLabel, customer.customerNumber),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            )
                        }
                    }

                    // Phone row
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Phone,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint =
                                if (phonePresent) {
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                        )
                        Text(
                            text = customer.phone ?: str(CustomerAddressKeys.noPhone),
                            style = MaterialTheme.typography.bodyMedium,
                            color =
                                if (phonePresent) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                        )
                    }

                    // Email row
                    val email = customer.email
                    if (email != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Email,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            )
                            Text(
                                text = email,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
            }
        }

        // 2. Delivery Toggle (only for takeaway tables)
        if (isTakeawayTable) {
            PosCard(
                variant = PosCardVariant.Plain,
                containerColor =
                    if (isDeliverySession) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                modifier = Modifier.fillMaxWidth(),
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
                            modifier = Modifier.weight(1f),
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
                            onCheckedChange = { onEvent(CustomerPanelEvent.ToggleDelivery(it)) },
                        )
                    }
                }
            }
        }

        // 3. Validation warnings (when delivery is ON)
        if (isDeliverySession && !phonePresent) {
            PosCard(
                variant = PosCardVariant.Plain,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                body {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = str(CustomerAddressKeys.phoneRequiredForDelivery),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        // 4. Address Section
        if (addresses.isNotEmpty()) {
            Text(
                text = str(CustomerAddressKeys.selectDeliveryAddress),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(addresses, key = { it.id }) { address ->
                    AddressCard(
                        address = address,
                        isSelected = selectedAddress?.id == address.id,
                        onClick = {
                            onEvent(
                                CustomerPanelEvent.SelectAddress(
                                    customer,
                                    address,
                                    isDeliverySession,
                                ),
                            )
                        },
                        currentLanguage = currentLanguage,
                    )
                }
            }
        } else if (isDeliverySession) {
            // No addresses + delivery ON: warning
            PosCard(
                variant = PosCardVariant.Plain,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                body {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = str(CustomerAddressKeys.addressRequiredForDelivery),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
        } else {
            // No addresses + delivery OFF: info text
            PosCard(
                variant = PosCardVariant.Plain,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                body {
                    Text(
                        text = str(CustomerAddressKeys.noAddressesOnFile),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            PosTextButton(
                text = str(CustomerAddressKeys.addNewAddress),
                onClick = { onEvent(CustomerPanelEvent.ShowAddAddressDialog) },
            )
            Spacer(Modifier.weight(1f))
        }

        // 5. Action Buttons
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Confirm button
            PosButton(
                text = str(CustomerAddressKeys.confirmSelection),
                onClick = { onEvent(CustomerPanelEvent.Confirm) },
                modifier = Modifier.fillMaxWidth(),
                enabled = confirmEnabled,
            )

            // Change Customer text button
            PosTextButton(
                text = str(CustomerAddressKeys.changeCustomer),
                onClick = { onEvent(CustomerPanelEvent.ClearCustomer) },
                modifier = Modifier.fillMaxWidth(),
            )

            // Remove Assignment button
            PosDangerButton(
                text = str(CustomerAddressKeys.removeAssignment),
                onClick = { onEvent(CustomerPanelEvent.RemoveAssignment) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CustomerSearchStep(
    error: String?,
    onEvent: (CustomerPanelEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var searchQuery by remember { mutableStateOf("") }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CustomerSearchField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            onCustomerSelected = { summary ->
                onEvent(CustomerPanelEvent.SelectCustomerById(summary.id))
            },
            label = str(CustomerAddressKeys.searchLabel),
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = Icons.Default.Search,
        )

        if (error != null) {
            PosCard(
                variant = PosCardVariant.Plain,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                body {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        if (searchQuery.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = str(CustomerAddressKeys.searchForCustomer),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = str(CustomerAddressKeys.searchInstructions),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
