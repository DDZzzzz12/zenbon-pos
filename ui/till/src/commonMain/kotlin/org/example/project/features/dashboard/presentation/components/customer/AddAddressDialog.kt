/**
 * Dialog for adding a new customer address.
 *
 * Provides form fields for address details including street, house number,
 * postal code, city, and additional delivery instructions.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.dashboard.presentation.components.customer

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.CustomerAddressKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.keyboardAwarePadding
import org.example.project.ui.components.*

/**
 * Dialog for adding a new customer address.
 *
 * @param customerId Customer UUID to associate address with
 * @param onDismiss Callback when dialog is dismissed
 * @param onSave Callback with address data when saved
 * @param isLoading Whether save operation is in progress
 * @param error Error message if any
 */
@Composable
fun AddAddressDialog(
    customerId: String,
    onDismiss: () -> Unit,
    onSave: (
        street: String,
        houseNumber: String,
        apartment: String?,
        postalCode: String,
        city: String,
        additionalInfo: String?,
        isDefault: Boolean,
    ) -> Unit,
    isLoading: Boolean = false,
    error: String? = null,
    modifier: Modifier = Modifier,
) {
    var street by remember { mutableStateOf("") }
    var houseNumber by remember { mutableStateOf("") }
    var apartment by remember { mutableStateOf("") }
    var postalCode by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var additionalInfo by remember { mutableStateOf("") }
    var isDefault by remember { mutableStateOf(false) }

    val isValid =
        street.isNotBlank() &&
            houseNumber.isNotBlank() &&
            postalCode.isNotBlank() &&
            city.isNotBlank()

    PosDialog(
        onDismiss = onDismiss,
        title = "Add Delivery Address",
        titleIcon = Icons.Default.AddLocationAlt,
        footer = {
            PosTextButton(
                text = str(CommonKeys.cancel),
                onClick = onDismiss,
                enabled = !isLoading,
            )
            PosButton(
                text = str(CustomerAddressKeys.saveAddress),
                onClick = {
                    onSave(
                        street.trim(),
                        houseNumber.trim(),
                        apartment.trim().ifBlank { null },
                        postalCode.trim(),
                        city.trim(),
                        additionalInfo.trim().ifBlank { null },
                        isDefault,
                    )
                },
                enabled = isValid,
                isLoading = isLoading,
            )
        },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .keyboardAwarePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Error display
            if (error != null) {
                PosCard(
                    variant = PosCardVariant.Plain,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ) {
                    body {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }

            // Street and house number in a row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PosTextField(
                    value = street,
                    onValueChange = { street = it },
                    label = "Street *",
                    leadingIcon = Icons.Default.LocationOn,
                    modifier = Modifier.weight(0.7f),
                    enabled = !isLoading,
                    isError = street.isBlank(),
                    supportingText =
                        if (street.isBlank()) {
                            { Text("Required") }
                        } else {
                            null
                        },
                )

                PosTextField(
                    value = houseNumber,
                    onValueChange = { houseNumber = it },
                    label = "No. *",
                    modifier = Modifier.weight(0.3f),
                    enabled = !isLoading,
                    isError = houseNumber.isBlank(),
                )
            }

            // Apartment field (optional)
            PosTextField(
                value = apartment,
                onValueChange = { apartment = it },
                label = "Apartment / Floor",
                leadingIcon = Icons.Default.Apartment,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading,
                placeholder = "e.g., Apt 4B, 3rd Floor",
            )

            // Postal code and city in a row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PosTextField(
                    value = postalCode,
                    onValueChange = { postalCode = it },
                    label = "Postal Code *",
                    modifier = Modifier.weight(0.4f),
                    keyboardType = KeyboardType.Number,
                    enabled = !isLoading,
                    isError = postalCode.isBlank(),
                )

                PosTextField(
                    value = city,
                    onValueChange = { city = it },
                    label = "City *",
                    modifier = Modifier.weight(0.6f),
                    enabled = !isLoading,
                    isError = city.isBlank(),
                )
            }

            // Additional delivery instructions
            PosTextField(
                value = additionalInfo,
                onValueChange = { additionalInfo = it },
                label = "Delivery Instructions",
                leadingIcon = Icons.Default.Info,
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 2,
                maxLines = 4,
                enabled = !isLoading,
                placeholder = "e.g., Ring the doorbell twice, use back entrance",
                imeAction = ImeAction.Done,
            )

            HorizontalDivider()

            // Set as default checkbox
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = isDefault,
                    onCheckedChange = { isDefault = it },
                    enabled = !isLoading,
                )
                Column {
                    Text(
                        text = str(CustomerAddressKeys.setAsDefaultAddress),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight =
                            if (isDefault) {
                                androidx.compose.ui.text.font.FontWeight.Medium
                            } else {
                                androidx.compose.ui.text.font.FontWeight.Normal
                            },
                    )
                    Text(
                        text = str(CustomerAddressKeys.defaultAddressHint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
