package org.example.project.ui.screens.billing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.project.core.data.models.PaymentType
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.models.setup.VoucherSource
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.core.presentation.components.PaymentTypeButton
import org.example.project.core.presentation.strings.keys.AddPaymentKeys
import org.example.project.core.presentation.strings.keys.BillingKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.core.utils.toUserInputDecimalOrNull
import org.example.project.features.dashboard.presentation.state.ValidatedVoucherInfo
import org.example.project.money.Decimal
import org.example.project.ui.components.PosAutoSizeText
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.screens.billing.components.VoucherCodeInputSection
import org.example.project.ui.screens.billing.components.VoucherRedeemOption
import org.example.project.ui.theme.PosSpacing

/**
 * Internal page state for the payment type picker dialog.
 */
private enum class PickerPage {
    TypeSelection,
    VoucherFlow,
}

/**
 * Payment type picker dialog.
 *
 * Shows a 2-column grid of payment types. Tapping a non-voucher type
 * immediately confirms the payment. Tapping voucher transitions to the
 * voucher input/validation flow within the same dialog.
 */
@Composable
fun PaymentTypePickerDialog(
    amount: Decimal,
    paymentTypes: List<PaymentType>,
    language: Language,
    onPaymentConfirmed: (paymentTypeId: Long, amount: Decimal) -> Unit,
    onDismiss: () -> Unit,
    isVoucherEnabled: Boolean = false,
    enabledVoucherSources: List<VoucherSource> = emptyList(),
    isValidatingVoucher: Boolean = false,
    validatedVoucher: ValidatedVoucherInfo? = null,
    voucherValidationError: String? = null,
    onVoucherConfirmed: (paymentTypeId: Long, amount: Decimal, voucherNumber: String, sourceType: VoucherSourceType) -> Unit = { _, _, _, _ -> },
    onValidateVoucher: (String, Decimal?, VoucherSourceType) -> Unit = { _, _, _ -> },
    onClearVoucherValidation: () -> Unit = {},
) {
    var currentPage by remember { mutableStateOf(PickerPage.TypeSelection) }

    val filteredTypes =
        remember(paymentTypes) {
            paymentTypes.filter { pt ->
                val upper = pt.name.uppercase()
                !upper.contains("GUTSCHEIN") && !upper.contains("VOUCHER")
            }
        }

    val voucherPaymentType =
        remember(paymentTypes) {
            paymentTypes.firstOrNull { pt ->
                val upper = pt.name.uppercase()
                upper.contains("GUTSCHEIN") || upper.contains("VOUCHER")
            }
        }

    when (currentPage) {
        PickerPage.TypeSelection -> {
            TypeSelectionPage(
                amount = amount,
                filteredTypes = filteredTypes,
                language = language,
                isVoucherEnabled = isVoucherEnabled && voucherPaymentType != null,
                onTypeSelected = { paymentType ->
                    onPaymentConfirmed(paymentType.id, amount)
                },
                onVoucherSelected = { currentPage = PickerPage.VoucherFlow },
                onDismiss = onDismiss,
            )
        }

        PickerPage.VoucherFlow -> {
            VoucherFlowPage(
                amount = amount,
                language = language,
                voucherPaymentType = voucherPaymentType,
                enabledVoucherSources = enabledVoucherSources,
                isValidatingVoucher = isValidatingVoucher,
                validatedVoucher = validatedVoucher,
                voucherValidationError = voucherValidationError,
                onVoucherConfirmed = onVoucherConfirmed,
                onValidateVoucher = onValidateVoucher,
                onClearVoucherValidation = onClearVoucherValidation,
                onBack = {
                    onClearVoucherValidation()
                    currentPage = PickerPage.TypeSelection
                },
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun TypeSelectionPage(
    amount: Decimal,
    filteredTypes: List<PaymentType>,
    language: Language,
    isVoucherEnabled: Boolean,
    onTypeSelected: (PaymentType) -> Unit,
    onVoucherSelected: () -> Unit,
    onDismiss: () -> Unit,
) {
    PosDialog(
        onDismiss = onDismiss,
        title = str(AddPaymentKeys.selectPaymentType),
        footer = {
            Spacer(modifier = Modifier.weight(1f))
            PosTextButton(
                text = str(CommonKeys.cancel),
                onClick = onDismiss,
            )
        },
    ) {
        Text(
            text = "${str(AddPaymentKeys.amountLabel)}: ${amount.toFormattedCurrency()}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.heightIn(max = 400.dp),
        ) {
            items(filteredTypes, key = { it.id }) { paymentType ->
                PaymentTypeButton(
                    paymentType = paymentType,
                    isSelected = false,
                    onClick = { onTypeSelected(paymentType) },
                    language = language,
                )
            }

            if (isVoucherEnabled) {
                item(key = "voucher") {
                    PosCard(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                        variant = PosCardVariant.Plain,
                        selected = false,
                        onClick = onVoucherSelected,
                    ) {
                        body {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(PosSpacing.sm),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CardGiftcard,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    PosAutoSizeText(
                                        text = str(BillingKeys.voucherLabel),
                                        style = MaterialTheme.typography.bodyLarge,
                                        minFontSize = 10.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoucherFlowPage(
    amount: Decimal,
    language: Language,
    voucherPaymentType: PaymentType?,
    enabledVoucherSources: List<VoucherSource>,
    isValidatingVoucher: Boolean,
    validatedVoucher: ValidatedVoucherInfo?,
    voucherValidationError: String?,
    onVoucherConfirmed: (paymentTypeId: Long, amount: Decimal, voucherNumber: String, sourceType: VoucherSourceType) -> Unit,
    onValidateVoucher: (String, Decimal?, VoucherSourceType) -> Unit,
    onClearVoucherValidation: () -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    var voucherRedeemOption by remember { mutableStateOf(VoucherRedeemOption.BILL_AMOUNT) }
    var customRedeemAmount by remember { mutableStateOf("") }
    var voucherCodeText by remember { mutableStateOf("") }
    var voucherAmountText by remember { mutableStateOf("") }
    // The source VoucherCodeInputSection shows first (its own default is the first enabled source):
    // with several sources and the first one LEGACY, `singleOrNull()` left the legacy fields on
    // screen while "Hinzufügen" stayed disabled until the operator re-tapped the visible chip.
    var selectedVoucherSource by remember {
        mutableStateOf<VoucherSourceType?>(enabledVoucherSources.firstOrNull()?.type)
    }

    val isConfirmEnabled =
        voucherPaymentType != null &&
            (
                (validatedVoucher != null) ||
                    (
                        selectedVoucherSource != null &&
                            selectedVoucherSource != VoucherSourceType.INTERNAL &&
                            voucherCodeText.isNotBlank() &&
                            typedVoucherAmountOrNull(voucherAmountText) != null
                    )
            )

    PosDialog(
        onDismiss = onDismiss,
        title = str(BillingKeys.voucherLabel),
        showCloseButton = false,
        footer = {
            PosTextButton(
                text = str(CommonKeys.cancel),
                onClick = {
                    onClearVoucherValidation()
                    onDismiss()
                },
            )
            Spacer(modifier = Modifier.weight(1f))
            PosButton(
                text = str(AddPaymentKeys.addButton),
                enabled = isConfirmEnabled,
                onClick = {
                    val paymentType = voucherPaymentType ?: return@PosButton
                    if (validatedVoucher != null) {
                        val redeemAmount =
                            when (voucherRedeemOption) {
                                VoucherRedeemOption.BILL_AMOUNT -> minOf(validatedVoucher.remainingBalance, amount)
                                VoucherRedeemOption.FULL_BALANCE -> validatedVoucher.remainingBalance
                                VoucherRedeemOption.CUSTOM -> {
                                    val custom = customRedeemAmount.toUserInputDecimalOrNull() ?: Decimal.ZERO
                                    minOf(custom, validatedVoucher.remainingBalance)
                                }
                            }
                        if (redeemAmount > Decimal.ZERO) {
                            onVoucherConfirmed(
                                paymentType.id,
                                redeemAmount,
                                validatedVoucher.voucherNumber,
                                validatedVoucher.source,
                            )
                        }
                    } else if (selectedVoucherSource != null && selectedVoucherSource != VoucherSourceType.INTERNAL) {
                        val amount = typedVoucherAmountOrNull(voucherAmountText)
                        if (amount != null && voucherCodeText.isNotBlank()) {
                            onVoucherConfirmed(
                                paymentType.id,
                                amount,
                                voucherCodeText,
                                selectedVoucherSource!!,
                            )
                        }
                    }
                },
            )
        },
    ) {
        // Back arrow + amount subtitle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // raw Material 3 by design: non-48dp size(32.dp) modifier not supported by PosNavIconButton
            IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = str(BillingKeys.backButton),
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = "${str(AddPaymentKeys.amountLabel)}: ${amount.toFormattedCurrency()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Voucher input section (reused from existing code)
        VoucherCodeInputSection(
            language = language,
            enabledSources = enabledVoucherSources,
            remainingAmount = amount,
            isValidating = isValidatingVoucher,
            validatedVoucher = validatedVoucher,
            validationError = voucherValidationError,
            redeemOption = voucherRedeemOption,
            customRedeemAmount = customRedeemAmount,
            voucherCode = voucherCodeText,
            voucherAmount = voucherAmountText,
            selectedSource = selectedVoucherSource,
            onVoucherCodeChange = { voucherCodeText = it },
            onVoucherAmountChange = { voucherAmountText = it },
            onSourceChange = { selectedVoucherSource = it },
            onRedeemOptionChange = { voucherRedeemOption = it },
            onCustomRedeemAmountChange = { customRedeemAmount = it },
            onValidate = onValidateVoucher,
            onClear = onClearVoucherValidation,
        )
    }
}

/**
 * The amount an operator typed for a voucher without a lookup (a LEGACY/EXTERNAL one), or null when
 * it is blank, unreadable or not positive. Typed input, so the German comma counts: the `Price` field
 * lets "10,00" in, and a strict `Decimal.parseOrNull` kept „Hinzufügen" disabled for it (QA rt55, NF-6).
 */
internal fun typedVoucherAmountOrNull(text: String): Decimal? = text.toUserInputDecimalOrNull()?.takeIf { it > Decimal.ZERO }
