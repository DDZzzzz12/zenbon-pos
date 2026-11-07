package org.example.project.features.dashboard.presentation.components.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.customer.CustomerAddressDto
import org.example.project.core.domain.dto.customer.CustomerDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.keys.CustomerAddressKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosDangerButton
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.theme.PosSpacing

/**
 * Confirmation step content.
 */
@Composable
fun ConfirmationContent(
    customer: CustomerDto.CustomerResponseDto,
    address: CustomerAddressDto.CustomerAddressResponseDto,
    isDeliverySession: Boolean,
    onConfirm: () -> Unit,
    onChangeCustomer: () -> Unit,
    onChangeAddress: () -> Unit,
    onRemoveAssignment: () -> Unit,
    currentLanguage: Language,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(PosSpacing.md),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Delivery indicator
        if (isDeliverySession) {
            PosCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                variant = PosCardVariant.Tonal,
            ) {
                body {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.DeliveryDining,
                            null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            str(CustomerAddressKeys.deliveryNotice),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }
            }
        }
        // Success icon
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Text(
            str(CustomerAddressKeys.selectionComplete),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        HorizontalDivider()

        // Customer details
        PosCard(
            modifier = Modifier.fillMaxWidth(),
            variant = PosCardVariant.Tonal,
        ) {
            body {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            str(CustomerAddressKeys.customerLabel),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PosTextButton(text = str(CustomerAddressKeys.changeLabel), onClick = onChangeCustomer)
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Person, null)
                        Column {
                            Text(
                                "${customer.firstName} ${customer.lastName}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            if (customer.phone != null) {
                                Text(
                                    customer.phone!!,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (customer.email != null) {
                                Text(
                                    customer.email!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Address details
        PosCard(
            modifier = Modifier.fillMaxWidth(),
            variant = PosCardVariant.Tonal,
        ) {
            body {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            str(CustomerAddressKeys.deliveryAddressLabel),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PosTextButton(text = str(CustomerAddressKeys.changeLabel), onClick = onChangeAddress)
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(Icons.Default.LocationOn, null)
                        Column {
                            Text(
                                "${address.street} ${address.houseNumber}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            if (!address.apartment.isNullOrBlank()) {
                                Text(
                                    str(CustomerAddressKeys.apartmentLabel, address.apartment!!),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                "${address.postalCode} ${address.city}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!address.additionalInfo.isNullOrBlank()) {
                                Text(
                                    address.additionalInfo!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // Action buttons
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Confirm button
            PosButton(
                text = str(CustomerAddressKeys.confirmSelection),
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
            )

            // Remove assignment button
            PosDangerButton(
                text = str(CustomerAddressKeys.removeAssignment),
                onClick = onRemoveAssignment,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
