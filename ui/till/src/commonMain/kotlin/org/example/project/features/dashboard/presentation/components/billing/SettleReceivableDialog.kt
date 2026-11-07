package org.example.project.features.dashboard.presentation.components.billing

import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.example.project.core.data.models.PaymentType
import org.example.project.core.domain.dto.salesinvoice.SalesInvoiceDto
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.SalesInvoiceTillKeys
import org.example.project.core.presentation.strings.str
import org.example.project.money.Decimal
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosPickerField
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextField

/**
 * Takes a payment at the counter against an open `POS_RECEIVABLE` invoice (slice 4c-2c).
 *
 * Deliberately small: an amount (defaulted to the whole open receivable) and a tender. Every rule —
 * that the tender must actually settle rather than open another receivable, and that the amount may
 * not exceed what is owed — is enforced by the server; a wrong choice comes back as an error rather
 * than being silently corrected here.
 *
 * The tender list is the register's active payment types, minus any that would themselves open a
 * receivable (`createsReceivable`) — settling a receivable by opening another is not a payment. The
 * server enforces the same rule with a 409, so this filter is the UI half of the guard, not the only
 * one.
 *
 * @param openAmount the invoice's open receivable, as a decimal string.
 * @param paymentTypes the register's active payment types.
 * @param onConfirm receives the settle request; the caller performs the call.
 */
@Composable
fun SettleReceivableDialog(
    openAmount: String,
    paymentTypes: List<PaymentType>,
    onDismiss: () -> Unit,
    onConfirm: (SalesInvoiceDto.SettleAtTillRequestDto) -> Unit,
) {
    var amount by remember { mutableStateOf(openAmount) }
    var selectedTender by remember { mutableStateOf<PaymentType?>(null) }
    var tenderMenuOpen by remember { mutableStateOf(false) }

    // The UI half of the both-levels guard: a tender that would itself open a receivable cannot
    // settle one. The server rejects it too (SALES_INVOICE_TENDER_NOT_SETTLING).
    val settlingTenders = paymentTypes.filter { !it.createsReceivable }

    val parsedAmount = Decimal.parseOrNull(amount.trim())
    val amountValid = parsedAmount != null && parsedAmount > Decimal.ZERO
    val canConfirm = amountValid && selectedTender != null

    PosDialog(
        onDismiss = onDismiss,
        title = str(SalesInvoiceTillKeys.settleDialogTitle),
        maxWidth = 440.dp,
        footer = {
            PosSecondaryButton(
                text = str(CommonKeys.cancel),
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            PosButton(
                text = str(SalesInvoiceTillKeys.settleConfirmButton),
                onClick = {
                    val tender = selectedTender ?: return@PosButton
                    onConfirm(
                        SalesInvoiceDto.SettleAtTillRequestDto(
                            paymentTypeId = tender.id,
                            amount = amount.trim(),
                        ),
                    )
                },
                modifier = Modifier.weight(1f),
                enabled = canConfirm,
            )
        },
    ) {
        Text(
            text = str(SalesInvoiceTillKeys.settleOpenAmountLabel, openAmount),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        PosTextField(
            value = amount,
            onValueChange = { amount = it },
            label = str(SalesInvoiceTillKeys.settleAmountLabel),
            modifier = Modifier.fillMaxWidth(),
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done,
        )

        Box(modifier = Modifier.fillMaxWidth()) {
            PosPickerField(
                value = selectedTender?.name ?: "",
                onClick = { tenderMenuOpen = true },
                label = str(SalesInvoiceTillKeys.settleTenderLabel),
                modifier = Modifier.fillMaxWidth(),
            )
            PosDropdown(
                expanded = tenderMenuOpen,
                onDismissRequest = { tenderMenuOpen = false },
            ) {
                settlingTenders.forEach { tender ->
                    PosDropdownItem(
                        text = tender.name,
                        onClick = {
                            selectedTender = tender
                            tenderMenuOpen = false
                        },
                    )
                }
            }
        }
    }
}
