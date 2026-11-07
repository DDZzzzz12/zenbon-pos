package org.example.project.ui.screens.billing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.features.dashboard.presentation.state.PaymentEntry
import org.example.project.money.Decimal
import org.example.project.ui.theme.PosSpacing

/**
 * Payment panel: a pinned amount header, then the payment list and the numpad sharing the rest of
 * the height by weight (2 : 3), so the list always keeps two fifths of it on every screen size and
 * scrolls inside that share. Before any payment exists the numpad takes all of it.
 *
 * Payment type selection is handled by PaymentTypePickerDialog,
 * triggered via onEnterPressed when the user confirms an amount.
 */
@Composable
fun PosBillingPaymentPanel(
    numpadInput: String,
    paidAmount: Decimal,
    remainingAmount: Decimal,
    totalAmount: Decimal,
    payments: List<PaymentEntry>,
    onNumpadKey: (String) -> Unit,
    onNumpadBackspace: () -> Unit,
    onNumpadClear: () -> Unit,
    onEnterPressed: () -> Unit,
    onRemovePayment: (String) -> Unit,
    modifier: Modifier = Modifier,
    tipAmount: Decimal = Decimal.ZERO,
) {
    Column(modifier = modifier.padding(12.dp)) {
        // Pinned: never scrolls, never squeezed away (E2E-2026-09-18 #5).
        PaymentAmountHeader(
            numpadInput = numpadInput,
            paidAmount = paidAmount,
            remainingAmount = remainingAmount,
            totalAmount = totalAmount,
            tipAmount = tipAmount,
        )
        Spacer(modifier = Modifier.height(SECTION_GAP))
        if (payments.isNotEmpty()) {
            PaymentList(
                payments = payments,
                onRemovePayment = onRemovePayment,
                modifier = Modifier.fillMaxWidth().weight(LIST_WEIGHT),
            )
        }
        NumpadSection(
            onNumpadKey = onNumpadKey,
            onNumpadBackspace = onNumpadBackspace,
            onNumpadClear = onNumpadClear,
            onEnterPressed = onEnterPressed,
            modifier = Modifier.fillMaxWidth().weight(NUMPAD_WEIGHT),
        )
    }
}

@Composable
private fun PaymentList(
    payments: List<PaymentEntry>,
    onRemovePayment: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(PosSpacing.sm),
    ) {
        payments.forEach { payment ->
            PosPaymentEntryCard(
                paymentTypeName = payment.paymentTypeName,
                amount = payment.amount,
                onRemove = { onRemovePayment(payment.id) },
            )
        }
    }
}

/**
 * The numpad, pinned to the bottom of its share. Its keys grow with the share only up to
 * [NUMPAD_MAX_HEIGHT], so a tall screen gets a gap above the numpad rather than oversized keys.
 */
@Composable
private fun NumpadSection(
    onNumpadKey: (String) -> Unit,
    onNumpadBackspace: () -> Unit,
    onNumpadClear: () -> Unit,
    onEnterPressed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.BottomCenter) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = NUMPAD_MAX_HEIGHT)) {
            HorizontalDivider()
            Spacer(modifier = Modifier.height(SECTION_GAP))
            PosNumpad(
                onKey = onNumpadKey,
                onBackspace = onNumpadBackspace,
                onClear = onNumpadClear,
                onEnter = onEnterPressed,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

private val SECTION_GAP = 8.dp
private val NUMPAD_MAX_HEIGHT = 250.dp
private const val LIST_WEIGHT = 2f
private const val NUMPAD_WEIGHT = 3f
