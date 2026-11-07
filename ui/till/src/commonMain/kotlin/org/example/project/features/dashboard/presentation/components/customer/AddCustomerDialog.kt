/**
 * Dialog for adding a new customer.
 *
 * Provides form fields for customer information including name, phone,
 * email, and consent preferences.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.dashboard.presentation.components.customer

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
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
import org.example.project.ui.components.PosDialog

/**
 * Dialog for adding a new customer.
 *
 * @param onDismiss Callback when dialog is dismissed
 * @param onSave Callback with customer data when saved
 * @param isLoading Whether save operation is in progress
 * @param error Error message if any
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCustomerDialog(
    onDismiss: () -> Unit,
    onSave: (
        firstName: String,
        lastName: String,
        phone: String?,
        email: String?,
        notes: String?,
        marketingConsent: Boolean,
        dataProcessingConsent: Boolean,
    ) -> Unit,
    isLoading: Boolean = false,
    error: String? = null,
    modifier: Modifier = Modifier,
) {
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var marketingConsent by remember { mutableStateOf(false) }
    var dataProcessingConsent by remember { mutableStateOf(true) }

    val isValid = firstName.isNotBlank() && lastName.isNotBlank()

    PosDialog(
        onDismiss = onDismiss,
        title = "Add New Customer",
        titleIcon = Icons.Default.PersonAdd,
        modifier = modifier,
        footer = {
            PosTextButton(
                text = str(CommonKeys.cancel),
                onClick = onDismiss,
                enabled = !isLoading,
            )
            PosButton(
                text = str(CustomerAddressKeys.saveCustomer),
                onClick = {
                    onSave(
                        firstName.trim(),
                        lastName.trim(),
                        phone.trim().ifBlank { null },
                        email.trim().ifBlank { null },
                        notes.trim().ifBlank { null },
                        marketingConsent,
                        dataProcessingConsent,
                    )
                },
                enabled = isValid,
                isLoading = isLoading,
            )
        },
    ) {
        // No verticalScroll here: PosDialog's body already scrolls, and a nested scroll container
        // is measured with infinite height and throws.
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

            // First name field (required)
            PosTextField(
                value = firstName,
                onValueChange = { firstName = it },
                label = "First Name *",
                leadingIcon = Icons.Default.Person,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading,
                isError = firstName.isBlank(),
                supportingText =
                    if (firstName.isBlank()) {
                        { Text("Required field") }
                    } else {
                        null
                    },
            )

            // Last name field (required)
            PosTextField(
                value = lastName,
                onValueChange = { lastName = it },
                label = "Last Name *",
                leadingIcon = Icons.Default.Person,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading,
                isError = lastName.isBlank(),
                supportingText =
                    if (lastName.isBlank()) {
                        { Text("Required field") }
                    } else {
                        null
                    },
            )

            // Phone field (optional)
            PosTextField(
                value = phone,
                onValueChange = { phone = it },
                label = "Phone Number",
                leadingIcon = Icons.Default.Phone,
                modifier = Modifier.fillMaxWidth(),
                keyboardType = KeyboardType.Phone,
                enabled = !isLoading,
            )

            // Email field (optional)
            PosTextField(
                value = email,
                onValueChange = { email = it },
                label = "Email Address",
                leadingIcon = Icons.Default.Email,
                modifier = Modifier.fillMaxWidth(),
                keyboardType = KeyboardType.Email,
                enabled = !isLoading,
            )

            // Notes field (optional)
            PosTextField(
                value = notes,
                onValueChange = { notes = it },
                label = "Notes",
                leadingIcon = Icons.AutoMirrored.Filled.Notes,
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 3,
                maxLines = 5,
                enabled = !isLoading,
                imeAction = ImeAction.Done,
            )

            HorizontalDivider()

            // Consent checkboxes
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = str(CustomerAddressKeys.consentSection),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = marketingConsent,
                        onCheckedChange = { marketingConsent = it },
                        enabled = !isLoading,
                    )
                    Text(
                        text = str(CustomerAddressKeys.marketingConsent),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = dataProcessingConsent,
                        onCheckedChange = { dataProcessingConsent = it },
                        enabled = !isLoading,
                    )
                    Text(
                        text = str(CustomerAddressKeys.dataProcessingConsent),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
