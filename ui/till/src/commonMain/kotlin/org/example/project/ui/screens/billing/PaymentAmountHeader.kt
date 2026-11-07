package org.example.project.ui.screens.billing

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.BillingKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.money.Decimal
import org.example.project.ui.components.formatMoneyForDisplay

/**
 * The typed amount and the paid / remaining / total line. Pinned above the payment list: it is the
 * one thing a cashier must never lose sight of while typing (E2E-2026-09-18 #5).
 *
 * @param totalAmount the bill after promotions, WITHOUT the tip — "Gesamt" is what the bill comes to,
 *   never what was tendered (F65).
 * @param tipAmount the overpayment the bill keeps as a tip; once positive the line names it
 *   ("davon Trinkgeld: 2,00 €"), because nothing else on the line says the extra became a tip (F54 #5).
 */
@Composable
fun PaymentAmountHeader(
    numpadInput: String,
    paidAmount: Decimal,
    remainingAmount: Decimal,
    totalAmount: Decimal,
    modifier: Modifier = Modifier,
    tipAmount: Decimal = Decimal.ZERO,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = str(BillingKeys.enterAmountLabel),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            // Placeholder in the display format every amount on this panel uses ("0,00"), not a literal.
            text = if (numpadInput.isBlank()) formatMoneyForDisplay("0", "") else numpadInput,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            color =
                if (numpadInput.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                } else {
                    MaterialTheme.colorScheme.primary
                },
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text =
                "${str(BillingKeys.paidLabel)} ${paidAmount.toFormattedCurrency()} " +
                    "· ${str(BillingKeys.remainingLabel)} ${remainingAmount.toFormattedCurrency()} " +
                    "· ${str(BillingKeys.totalLabel)} ${totalAmount.toFormattedCurrency()}" +
                    if (tipAmount > Decimal.ZERO) " · ${str(BillingKeys.tipPortionLabel, tipAmount.toFormattedCurrency())}" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
