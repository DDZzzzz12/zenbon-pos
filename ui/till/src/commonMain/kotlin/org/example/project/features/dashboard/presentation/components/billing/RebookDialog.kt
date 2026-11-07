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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import org.example.project.core.data.models.PaymentType
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.models.setup.VoucherConfig
import org.example.project.core.domain.models.setup.VoucherSource
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.AddPaymentKeys
import org.example.project.core.presentation.strings.keys.BillReceiptKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.getLocalizedName
import org.example.project.core.utils.rebookBillTotal
import org.example.project.core.utils.rebookTender
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.core.utils.toUserInputDecimalOrNull
import org.example.project.core.utils.withDecimalSeparatorOf
import org.example.project.features.dashboard.presentation.state.PaymentEntry
import org.example.project.features.dashboard.presentation.state.ValidatedVoucherInfo
import org.example.project.money.Decimal
import org.example.project.money.MoneyRounding
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.PosTextField
import org.example.project.ui.screens.billing.PaymentTypePickerDialog

/**
 * Zahlartwechsel dialog: the bill is stornoed and booked again (Storno + Neubuchung) with the tender
 * and the WHOLE tip entered here.
 *
 * Shows the bill's current tender and starts the new one from it ([rebookEntries]: its payments,
 * voucher legs with their numbers, so a kept voucher is not re-validated) and the tip from the
 * bill's own. The user removes payments here and adds them through the table billing screen's own
 * [PaymentTypePickerDialog] — the same type grid and the same voucher flow, every enabled voucher
 * source included (an INTERNAL voucher is validated there, a LEGACY/EXTERNAL one is taken by number
 * and amount, exactly as on the billing screen). The amount field plays the billing numpad's part:
 * blank means "the rest". Confirmation is enabled only on the `RebookTender` rule the server refuses
 * on (rule 8, via `acceptsRebook`): well formed, covering exactly the bill total (amount minus
 * promotions) plus the tip, different from the bill's current payments, tip or voucher numbers,
 * and — without [mayLowerTip] (`BILL_VOID`) — not below the bill's own tip.
 *
 * While the payment picker is open this dialog is hidden so the two never stack; the accumulated
 * [entries] survive because this composable stays in the composition tree.
 */
@Composable
fun RebookDialog(
    bill: BillDto.BillResponseDto,
    language: Language,
    paymentTypes: List<PaymentType>,
    voucherConfig: VoucherConfig?,
    enabledVoucherSources: List<VoucherSource>,
    isValidatingVoucher: Boolean,
    validatedVoucher: ValidatedVoucherInfo?,
    voucherValidationError: String?,
    /** The session holds `BILL_VOID`; without it the tip may be kept or raised, not lowered. */
    mayLowerTip: Boolean,
    onValidateVoucher: (String, Decimal?, VoucherSourceType) -> Unit,
    onClearVoucherValidation: () -> Unit,
    onConfirm: (entries: List<PaymentEntry>, tip: Decimal) -> Unit,
    onDismiss: () -> Unit,
) {
    val billTotal: Decimal = bill.rebookBillTotal()
    var tipText by remember(bill.billId) { mutableStateOf(prefilledTip(bill, language)) }
    val tip: Decimal? = parseTip(tipText)
    val targetAmount: Decimal = billTotal + (tip ?: Decimal.ZERO)
    var entries by remember(bill.billId) { mutableStateOf(bill.rebookEntries(language)) }
    var amountText by remember(bill.billId) { mutableStateOf("") }
    // Non-null while the payment picker is open: the amount the picked payment will carry.
    var pickerAmount by remember { mutableStateOf<Decimal?>(null) }
    val enteredAmount = entries.fold(Decimal.ZERO) { acc, entry -> acc + entry.amount }
    val remainingAmount = targetAmount - enteredAmount

    if (pickerAmount == null) {
        PosDialog(
            onDismiss = onDismiss,
            title = str(BillReceiptKeys.changePaymentTitle),
            showCloseButton = false,
            footer = {
                PosTextButton(text = str(CommonKeys.cancel), onClick = onDismiss)
                PosButton(
                    text = str(BillReceiptKeys.changePaymentConfirm),
                    enabled = tip != null && bill.acceptsRebook(entries, tip, mayLowerTip),
                    onClick = { if (tip != null) onConfirm(entries, tip) },
                )
            },
        ) {
            // Current tender
            Text(
                text = str(BillReceiptKeys.changePaymentCurrent),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            bill.payments.forEach { payment ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = payment.getLocalizedName(LocalAppLanguage.current),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = Decimal.parseOrNull(payment.amount)?.toFormattedCurrency() ?: payment.amount,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // The new bill's WHOLE tip (not an addition): it raises the target the new tender must reach.
            PosTextField(
                value = tipText,
                onValueChange = { tipText = it },
                label = str(BillReceiptKeys.changePaymentTip),
                keyboardType = KeyboardType.Decimal,
                isError = tip == null,
                errorMessage = if (tip == null) str(BillReceiptKeys.changePaymentTipInvalid) else null,
                imeAction = ImeAction.Done,
                modifier = Modifier.fillMaxWidth(),
            )
            if (tip != null && bill.rebookTipDecreaseRefused(tip, mayLowerTip)) {
                Text(
                    text = str(BillReceiptKeys.rebookTipDecreaseHint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // New tender being built
            Text(
                text = str(BillReceiptKeys.changePaymentNew),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            entries.forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = entry.paymentTypeName + (entry.voucherNumber?.let { " ($it)" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = entry.amount.toFormattedCurrency(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        PosTextButton(
                            text = str(CommonKeys.delete),
                            onClick = { entries = entries - entry },
                        )
                    }
                }
            }

            // Remaining amount indicator
            Text(
                text = str(BillReceiptKeys.changePaymentRemaining, remainingAmount.toFormattedCurrency()),
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (remainingAmount == Decimal.ZERO) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
            )

            // The next payment's amount, like the billing screen's numpad: blank takes the rest.
            val nextAmount = rebookNextPaymentAmount(amountText, remainingAmount)
            PosTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = str(AddPaymentKeys.amountLabel),
                placeholder = remainingAmount.coerceAtLeast(Decimal.ZERO).toFormattedCurrency(),
                keyboardType = KeyboardType.Decimal,
                isError = amountText.isNotBlank() && nextAmount == null,
                imeAction = ImeAction.Done,
                modifier = Modifier.fillMaxWidth(),
            )
            PosSecondaryButton(
                text = str(BillReceiptKeys.changePaymentAddPayment),
                enabled = nextAmount != null,
                onClick = { pickerAmount = nextAmount },
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = str(BillReceiptKeys.rebookWarning, bill.billNumber),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    pickerAmount?.let { amount ->
        val closePicker = {
            pickerAmount = null
            onClearVoucherValidation()
        }
        PaymentTypePickerDialog(
            amount = amount,
            paymentTypes = paymentTypes,
            language = language,
            onPaymentConfirmed = { paymentTypeId, paid ->
                val type = paymentTypes.first { it.id == paymentTypeId }
                entries = entries +
                    PaymentEntry(
                        id = "chg-${entries.size}-$paymentTypeId-$paid",
                        paymentTypeId = paymentTypeId,
                        paymentTypeName = type.getName(language),
                        amount = paid,
                    )
                amountText = ""
                closePicker()
            },
            onDismiss = closePicker,
            isVoucherEnabled = voucherConfig?.enabled == true,
            enabledVoucherSources = enabledVoucherSources,
            isValidatingVoucher = isValidatingVoucher,
            validatedVoucher = validatedVoucher,
            voucherValidationError = voucherValidationError,
            onVoucherConfirmed = { paymentTypeId, paid, voucherNumber, sourceType ->
                val type = paymentTypes.first { it.id == paymentTypeId }
                entries = entries +
                    PaymentEntry(
                        id = "chg-${entries.size}-$paymentTypeId-$paid-v",
                        paymentTypeId = paymentTypeId,
                        paymentTypeName = type.getName(language),
                        amount = paid,
                        voucherNumber = voucherNumber,
                        voucherSource = sourceType,
                    )
                amountText = ""
                closePicker()
            },
            onValidateVoucher = onValidateVoucher,
            onClearVoucherValidation = onClearVoucherValidation,
        )
    }
}

/** The typed tip: blank is no tip; null when unparseable, negative or finer than a cent. */
private fun parseTip(raw: String): Decimal? {
    if (raw.isBlank()) return Decimal.ZERO
    val tip = raw.toUserInputDecimalOrNull() ?: return null
    if (tip.isNegative || tip != tip.setScale(2, MoneyRounding.DOWN)) return null
    return tip
}

/** The tip field starts at the bill's own whole tip, to the cent. */
private fun prefilledTip(
    bill: BillDto.BillResponseDto,
    language: Language,
): String {
    val tip = bill.rebookTender().tip
    return tip.setScale(2, MoneyRounding.HALF_UP).toPlainString().withDecimalSeparatorOf(language)
}
