/**
 * Shared payment entry panel composable.
 *
 * Provides a reusable payment management section including:
 * - Header with add payment button
 * - Scrollable payment entries list with remove capability
 * - Payment summary showing paid amount and remaining balance
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.ui.screens.billing.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.features.dashboard.presentation.state.PaymentEntry
import org.example.project.money.Decimal
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosIconButton

/**
 * Complete payment management panel.
 *
 * Displays a card with header, scrollable payment list, and payment summary.
 * Suitable for tablet/desktop layouts where the payment section is
 * a self-contained panel.
 *
 * @param payments List of payment entries to display
 * @param paidAmount Total paid amount
 * @param tipAmount Tip amount
 * @param remainingAmount Remaining amount to be paid
 * @param onAddPayment Callback to open the add payment dialog
 * @param onRemovePayment Callback to remove a payment by ID
 * @param language Current language for localized strings
 * @param modifier Modifier for the root Card
 */
@Composable
fun PaymentEntryPanel(
    payments: List<PaymentEntry>,
    paidAmount: Decimal,
    tipAmount: Decimal,
    remainingAmount: Decimal,
    onAddPayment: () -> Unit,
    onRemovePayment: (String) -> Unit,
    language: Language,
    modifier: Modifier = Modifier,
) {
    PosCard(
        modifier = modifier,
        variant = PosCardVariant.Plain,
        tonalElevation = 2.dp,
    ) {
        body {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header with add button
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = getPaymentsTitle(language),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    // raw Material 3 by design: multi-child content (Icon+Spacer+Text) not expressible via PosTonalButton icon/text params
                    FilledTonalButton(
                        onClick = onAddPayment,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = getAddPaymentButton(language),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }

                HorizontalDivider()

                // Scrollable payment list or empty state
                if (payments.isNotEmpty()) {
                    LazyColumn(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(payments, key = { it.id }) { payment ->
                            PaymentItemCard(
                                payment = payment,
                                onRemove = onRemovePayment,
                                language = language,
                            )
                        }
                    }
                } else {
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = getNoPaymentsAdded(language),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                HorizontalDivider()

                // Payment summary
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Paid amount
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = getPaidLabel(language),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                        )
                        Text(
                            text = paidAmount.toFormattedCurrency(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    // Remaining amount
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = getRemainingLabel(language),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        Text(
                            text = remainingAmount.toFormattedCurrency(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color =
                                if (remainingAmount == Decimal.ZERO) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                        )
                    }

                    // Error hint when payments don't cover total
                    if (payments.isNotEmpty() && remainingAmount != Decimal.ZERO) {
                        Text(
                            text = getRemainingHint(language, remainingAmount.toFormattedCurrency()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Individual payment item card with remove button.
 *
 * Displays payment type name, amount, and optional voucher details.
 * Uses tertiary container color for voucher payments.
 *
 * @param payment Payment entry to display
 * @param onRemove Callback with payment ID when remove is clicked
 * @param language Current language for localized strings
 * @param modifier Modifier for the card
 */
@Composable
fun PaymentItemCard(
    payment: PaymentEntry,
    onRemove: (String) -> Unit,
    language: Language,
    modifier: Modifier = Modifier,
) {
    PosCard(
        modifier = modifier,
        variant = PosCardVariant.Plain,
        containerColor =
            if (payment.isVoucherPayment) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
    ) {
        body {
            Column {
                Text(
                    text = payment.paymentTypeName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = payment.amount.toFormattedCurrency(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )

                // Voucher details
                if (payment.isVoucherPayment && payment.voucherNumber != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text =
                            when (language) {
                                Language.GERMAN -> "Gutschein: ${payment.voucherNumber}"
                                Language.ENGLISH -> "Voucher: ${payment.voucherNumber}"
                                Language.CHINESE -> "代金券: ${payment.voucherNumber}"
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }
        trailing {
            PosIconButton(
                icon = Icons.Default.Close,
                contentDescription = getRemovePaymentButton(language),
                onClick = { onRemove(payment.id) },
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// ========== Private string helpers ==========

private fun getPaymentsTitle(language: Language): String =
    when (language) {
        Language.GERMAN -> "Zahlungen"
        Language.ENGLISH -> "Payments"
        Language.CHINESE -> "支付"
    }

private fun getAddPaymentButton(language: Language): String =
    when (language) {
        Language.GERMAN -> "Hinzufügen"
        Language.ENGLISH -> "Add"
        Language.CHINESE -> "添加"
    }

private fun getNoPaymentsAdded(language: Language): String =
    when (language) {
        Language.GERMAN -> "Keine Zahlungen hinzugefügt"
        Language.ENGLISH -> "No payments added"
        Language.CHINESE -> "未添加支付"
    }

private fun getPaidLabel(language: Language): String =
    when (language) {
        Language.GERMAN -> "Bezahlt:"
        Language.ENGLISH -> "Paid:"
        Language.CHINESE -> "已付："
    }

private fun getRemainingLabel(language: Language): String =
    when (language) {
        Language.GERMAN -> "Verbleibend:"
        Language.ENGLISH -> "Remaining:"
        Language.CHINESE -> "剩余："
    }

private fun getRemovePaymentButton(language: Language): String =
    when (language) {
        Language.GERMAN -> "Zahlung entfernen"
        Language.ENGLISH -> "Remove payment"
        Language.CHINESE -> "移除支付"
    }

private fun getRemainingHint(
    language: Language,
    amount: String,
): String =
    when (language) {
        Language.GERMAN -> "Rest: $amount"
        Language.ENGLISH -> "Remaining: $amount"
        Language.CHINESE -> "余额: $amount"
    }
