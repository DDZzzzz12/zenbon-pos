package org.example.project.ui.screens.billing.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.models.setup.VoucherSource
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.core.presentation.strings.keys.AccessibilityKeys
import org.example.project.core.presentation.strings.keys.AddPaymentKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.core.utils.toUserInputDecimalOrNull
import org.example.project.features.dashboard.presentation.state.ValidatedVoucherInfo
import org.example.project.money.Decimal
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.PosTextFieldType
import kotlin.collections.forEach

/**
 * Voucher code input section with validation.
 *
 * Allows user to:
 * 1. Select voucher source (if multiple enabled)
 * 2. Enter voucher code
 * 3. Validate voucher (INTERNAL only)
 * 4. Enter amount directly (LEGACY/EXTERNAL)
 * 5. View validation results with smart tip calculation
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoucherCodeInputSection(
    language: Language,
    enabledSources: List<VoucherSource>,
    remainingAmount: Decimal,
    isValidating: Boolean,
    validatedVoucher: ValidatedVoucherInfo?,
    validationError: String?,
    redeemOption: VoucherRedeemOption,
    customRedeemAmount: String,
    voucherCode: String,
    voucherAmount: String,
    selectedSource: VoucherSourceType?,
    onVoucherCodeChange: (String) -> Unit,
    onVoucherAmountChange: (String) -> Unit,
    onSourceChange: (VoucherSourceType) -> Unit,
    onRedeemOptionChange: (VoucherRedeemOption) -> Unit,
    onCustomRedeemAmountChange: (String) -> Unit,
    onValidate: (String, Decimal?, VoucherSourceType) -> Unit,
    onClear: () -> Unit,
) {
    var selectedSourceObj by remember { mutableStateOf<VoucherSource?>(enabledSources.firstOrNull()) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Source Selection (if multiple sources enabled)
        if (enabledSources.size > 1) {
            Text(
                text = str(AddPaymentKeys.voucherSourceLabel),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                enabledSources.forEach { source ->
                    FilterChip(
                        selected = selectedSourceObj?.type == source.type,
                        onClick = {
                            selectedSourceObj = source
                            onSourceChange(source.type)
                            onClear()
                        },
                        label = { Text(source.name) },
                    )
                }
            }
        }

        // Voucher Code Input - different UI based on source type
        selectedSourceObj?.let { source ->
            when (source.type) {
                VoucherSourceType.INTERNAL -> {
                    InternalVoucherInput(
                        voucherCode = voucherCode,
                        onVoucherCodeChange = {
                            onVoucherCodeChange(it)
                            if (validatedVoucher != null || validationError != null) {
                                onClear()
                            }
                        },
                        language = language,
                        enabled = !isValidating,
                    )
                }
                else -> {
                    GeneralVoucherInput(
                        voucherCode = voucherCode,
                        voucherAmount = voucherAmount,
                        onVoucherCodeChange = {
                            onVoucherCodeChange(it.uppercase())
                            if (validatedVoucher != null || validationError != null) {
                                onClear()
                            }
                        },
                        onVoucherAmountChange = onVoucherAmountChange,
                        language = language,
                        enabled = !isValidating,
                    )
                }
            }
        }

        // Validate Button (only for INTERNAL vouchers)
        if (validatedVoucher == null && validationError == null && selectedSourceObj?.type == VoucherSourceType.INTERNAL) {
            PosButton(
                text = if (isValidating) str(AddPaymentKeys.validateVoucherButtonValidating) else str(AddPaymentKeys.validateVoucherButton),
                onClick = {
                    val source = selectedSourceObj
                    if (source != null) {
                        onValidate(voucherCode, null, source.type)
                    }
                },
                enabled =
                    !isValidating &&
                        voucherCode.isNotBlank() &&
                        selectedSourceObj != null,
                isLoading = isValidating,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Validation Error Display
        validationError?.let { error ->
            PosCard(
                modifier = Modifier.fillMaxWidth(),
                variant = PosCardVariant.Plain,
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ) {
                body {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = str(AddPaymentKeys.validationFailedTitle),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        PosIconButton(
                            icon = Icons.Default.Close,
                            contentDescription = str(AccessibilityKeys.clear),
                            onClick = onClear,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }

        // Validation Success Display
        validatedVoucher?.let { voucher ->
            PosCard(
                modifier = Modifier.fillMaxWidth(),
                variant = PosCardVariant.Plain,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) {
                body {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = str(AddPaymentKeys.voucherValidTitle),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )

                        HorizontalDivider()

                        // Voucher Details
                        VoucherDetailRow(
                            str(AddPaymentKeys.voucherNumberFieldLabel),
                            voucher.voucherNumber,
                        )
                        VoucherDetailRow(
                            str(AddPaymentKeys.voucherOriginalValueLabel),
                            voucher.originalValue.toFormattedCurrency(),
                        )
                        VoucherDetailRow(
                            str(AddPaymentKeys.voucherRemainingLabel),
                            voucher.remainingBalance.toFormattedCurrency(),
                        )

                        // Redeem Amount Selection (when balance > bill amount)
                        val maxTipAmount = (voucher.remainingBalance - remainingAmount).coerceAtLeast(org.example.project.money.Decimal.ZERO)
                        if (maxTipAmount > org.example.project.money.Decimal.ZERO) {
                            HorizontalDivider()

                            PosCard(
                                variant = PosCardVariant.Plain,
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            ) {
                                body {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            text =
                                                when (language) {
                                                    Language.GERMAN -> "Einlösungsbetrag"
                                                    Language.ENGLISH -> "Redeem Amount"
                                                    Language.CHINESE -> "兑换金额"
                                                },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            fontWeight = FontWeight.Bold,
                                        )

                                        // Option 1: Redeem only bill amount
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            RadioButton(
                                                selected = redeemOption == VoucherRedeemOption.BILL_AMOUNT,
                                                onClick = { onRedeemOptionChange(VoucherRedeemOption.BILL_AMOUNT) },
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Column {
                                                Text(
                                                    text =
                                                        when (language) {
                                                            Language.GERMAN -> "Nur Rechnungsbetrag"
                                                            Language.ENGLISH -> "Bill Amount Only"
                                                            Language.CHINESE -> "仅账单金额"
                                                        },
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                )
                                                Text(
                                                    text = remainingAmount.toFormattedCurrency(),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                )
                                            }
                                        }

                                        // Option 2: Redeem full voucher balance (creates tip)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            RadioButton(
                                                selected = redeemOption == VoucherRedeemOption.FULL_BALANCE,
                                                onClick = { onRedeemOptionChange(VoucherRedeemOption.FULL_BALANCE) },
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Column {
                                                Text(
                                                    text =
                                                        when (language) {
                                                            Language.GERMAN -> "Vollen Betrag einlösen"
                                                            Language.ENGLISH -> "Redeem Full Amount"
                                                            Language.CHINESE -> "兑换全额"
                                                        },
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                )
                                                Text(
                                                    text = voucher.remainingBalance.toFormattedCurrency(),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                )
                                            }
                                        }

                                        // Option 3: Custom amount
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            RadioButton(
                                                selected = redeemOption == VoucherRedeemOption.CUSTOM,
                                                onClick = { onRedeemOptionChange(VoucherRedeemOption.CUSTOM) },
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text =
                                                        when (language) {
                                                            Language.GERMAN -> "Benutzerdefinierter Betrag"
                                                            Language.ENGLISH -> "Custom Amount"
                                                            Language.CHINESE -> "自定义金额"
                                                        },
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                )
                                                if (redeemOption == VoucherRedeemOption.CUSTOM) {
                                                    Spacer(Modifier.height(4.dp))
                                                    PosTextField(
                                                        value = customRedeemAmount,
                                                        onValueChange = onCustomRedeemAmountChange,
                                                        label = "0.00",
                                                        textFieldType = PosTextFieldType.Price,
                                                        imeAction = ImeAction.Done,
                                                        modifier = Modifier.fillMaxWidth(),
                                                        colors =
                                                            OutlinedTextFieldDefaults.colors(
                                                                focusedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                                                unfocusedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                                            ),
                                                    )
                                                }
                                            }
                                        }

                                        // Show calculated tip amount
                                        val calculatedTipAmount =
                                            when (redeemOption) {
                                                VoucherRedeemOption.FULL_BALANCE -> maxTipAmount
                                                VoucherRedeemOption.CUSTOM -> {
                                                    val customAmount = customRedeemAmount.toUserInputDecimalOrNull() ?: Decimal.ZERO
                                                    (customAmount - remainingAmount).coerceAtLeast(Decimal.ZERO)
                                                }
                                                else -> Decimal.ZERO
                                            }

                                        if (calculatedTipAmount > Decimal.ZERO) {
                                            HorizontalDivider()
                                            Text(
                                                text = str(AddPaymentKeys.autoTipLabel, calculatedTipAmount.toFormattedCurrency()),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            )
                                        }
                                    }
                                } // body
                            }
                        }
                    } // Column
                } // body
            }
        }
    }
}

/**
 * Internal voucher input with structured format: G[YEAR]-[NUMBER].
 *
 * Provides separate text fields for year and number components with
 * automatic formatting and validation.
 */
@Composable
private fun InternalVoucherInput(
    voucherCode: String,
    onVoucherCodeChange: (String) -> Unit,
    language: Language,
    enabled: Boolean,
) {
    // Parse existing voucher code or use defaults
    val parts = voucherCode.removePrefix("G").split("-")
    var yearText by remember { mutableStateOf(parts.getOrNull(0)?.take(4) ?: "") }
    var numberText by remember { mutableStateOf(parts.getOrNull(1)?.take(4) ?: "") }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = str(AddPaymentKeys.voucherNumberLabel),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // G prefix
            Text(
                text = "G",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            // Year input
            PosTextField(
                value = yearText,
                onValueChange = { newValue ->
                    if (newValue.length <= 4 && newValue.all { it.isDigit() }) {
                        yearText = newValue
                        onVoucherCodeChange("G$yearText-$numberText")
                    }
                },
                label = "2025",
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
                enabled = enabled,
            )

            // Separator
            Text(
                text = "-",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            // Number input
            PosTextField(
                value = numberText,
                onValueChange = { newValue ->
                    if (newValue.length <= 4 && newValue.all { it.isDigit() }) {
                        numberText = newValue
                        onVoucherCodeChange("G$yearText-$numberText")
                    }
                },
                label = "0001",
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
                modifier = Modifier.weight(1f),
                enabled = enabled,
            )
        }

        Text(
            text = str(AddPaymentKeys.voucherNumberFormat),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * General voucher input for non-internal sources.
 *
 * Provides text fields for entering voucher codes and redeemed amounts
 * for external, legacy, or other voucher sources.
 */
@Composable
private fun GeneralVoucherInput(
    voucherCode: String,
    voucherAmount: String,
    onVoucherCodeChange: (String) -> Unit,
    onVoucherAmountChange: (String) -> Unit,
    language: Language,
    enabled: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Voucher Number Input
        PosTextField(
            value = voucherCode,
            onValueChange = onVoucherCodeChange,
            label = str(AddPaymentKeys.voucherNumberLabel),
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
        )

        // Redeemed Amount Input
        PosTextField(
            value = voucherAmount,
            onValueChange = onVoucherAmountChange,
            label = str(AddPaymentKeys.amountLabel),
            textFieldType = PosTextFieldType.Price,
            imeAction = ImeAction.Done,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
        )
    }
}

@Composable
private fun VoucherDetailRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Voucher redeem amount options.
 */
enum class VoucherRedeemOption {
    BILL_AMOUNT, // Redeem only the bill amount
    FULL_BALANCE, // Redeem full voucher balance (creates tip)
    CUSTOM, // User enters custom amount
}
