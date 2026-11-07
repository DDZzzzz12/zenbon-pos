package org.example.project.features.dashboard.presentation.components.billing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.core.presentation.components.customer.CustomerSearchField
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.SalesInvoiceTillKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextField

/**
 * Collects the §14 Abs. 4 UStG buyer block for an invoice over an already-settled bill.
 *
 * **Buyer fields only, by design.** Lines, quantities, VAT and totals are snapshotted from the bill
 * by the server and are not editable here — an invoice that disagreed with its own TSE-signed
 * receipt is exactly what a Betriebsprüfer looks for. That is also what keeps this a till action
 * rather than the invoice editor adminApp owns.
 *
 * @param billId the settled bill the invoice is issued for.
 * @param onConfirm receives the complete create request; the caller issues and prints it.
 */
@Composable
fun SalesInvoiceBuyerDialog(
    billId: Long,
    onDismiss: () -> Unit,
    onConfirm: (SalesInvoiceDto.CreateFromBillRequestDto) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var street by remember { mutableStateOf("") }
    var zip by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("") }
    var vatId by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }

    PosDialog(
        onDismiss = onDismiss,
        title = str(SalesInvoiceTillKeys.dialogTitle),
        maxWidth = 520.dp,
        footer = {
            PosSecondaryButton(
                text = str(CommonKeys.cancel),
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            PosButton(
                text = str(SalesInvoiceTillKeys.confirmButton),
                onClick = {
                    onConfirm(
                        SalesInvoiceDto.CreateFromBillRequestDto(
                            billId = billId,
                            buyerName = name.trim(),
                            buyerVatId = vatId.trim().ifBlank { null },
                            buyerStreet = street.trim().ifBlank { null },
                            buyerZip = zip.trim().ifBlank { null },
                            buyerCity = city.trim().ifBlank { null },
                            buyerCountry = country.trim().ifBlank { null },
                            buyerEmail = email.trim().ifBlank { null },
                        ),
                    )
                },
                modifier = Modifier.weight(1f),
                enabled = name.isNotBlank(),
            )
        },
    ) {
        Text(
            text = str(SalesInvoiceTillKeys.buyerSectionTitle),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        CustomerSearchField(
            value = name,
            onValueChange = { name = it },
            onCustomerSelected = { customer -> name = customer.fullName },
            label = str(SalesInvoiceTillKeys.buyerName),
            modifier = Modifier.fillMaxWidth(),
            // The buyer name is a §14 field: a floating suggestion list covered the street field, and
            // the tap meant for it replaced the typed name with a customer's (emu14 NOTE-4).
            inlineSuggestions = true,
        )

        PosTextField(
            value = street,
            onValueChange = { street = it },
            label = str(SalesInvoiceTillKeys.buyerStreet),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PosTextField(
                value = zip,
                onValueChange = { zip = it },
                label = str(SalesInvoiceTillKeys.buyerZip),
                modifier = Modifier.weight(1f),
            )
            PosTextField(
                value = city,
                onValueChange = { city = it },
                label = str(SalesInvoiceTillKeys.buyerCity),
                modifier = Modifier.weight(2f),
            )
        }

        PosTextField(
            value = country,
            onValueChange = { country = it },
            label = str(SalesInvoiceTillKeys.buyerCountry),
            modifier = Modifier.fillMaxWidth(),
        )

        PosTextField(
            value = vatId,
            onValueChange = { vatId = it },
            label = str(SalesInvoiceTillKeys.buyerVatId),
            modifier = Modifier.fillMaxWidth(),
        )

        PosTextField(
            value = email,
            onValueChange = { email = it },
            label = str(SalesInvoiceTillKeys.buyerEmail),
            modifier = Modifier.fillMaxWidth(),
            imeAction = ImeAction.Done,
        )

        Text(
            text = str(SalesInvoiceTillKeys.amountsNotEditableHint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = str(SalesInvoiceTillKeys.requiredFieldsHint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
