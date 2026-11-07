package org.example.project.features.reports.presentation.components.zBon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.zBon.TipPayoutMethod
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.TipPayoutKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.features.reports.presentation.state.TipPayoutAmountCheck
import org.example.project.features.reports.presentation.state.TipPayoutDraft
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.PosTextField

/**
 * Confirms one tip payout. The amount opens pre-filled with the whole open amount; confirming is
 * disabled while the amount fails [TipPayoutAmountCheck] or the request is in flight.
 */
@Composable
fun TipPayoutDialog(
    draft: TipPayoutDraft,
    onAmountChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onMethodChange: (TipPayoutMethod) -> Unit = {},
) {
    val check = draft.check
    val remaining = draft.remaining.toFormattedCurrency()
    PosDialog(
        onDismiss = onDismiss,
        title = str(TipPayoutKeys.dialogTitle),
        dismissOnClickOutside = !draft.isSubmitting,
        footer = {
            PosTextButton(
                text = str(CommonKeys.cancel),
                onClick = onDismiss,
                enabled = !draft.isSubmitting,
            )
            PosButton(
                text = str(TipPayoutKeys.confirm),
                onClick = onConfirm,
                enabled = check == TipPayoutAmountCheck.VALID && !draft.isSubmitting,
                isLoading = draft.isSubmitting,
            )
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = str(TipPayoutKeys.dialogMessage, draft.employeeName, remaining))
            PosTextField(
                value = draft.amount,
                onValueChange = onAmountChange,
                label = str(TipPayoutKeys.amountLabel),
                enabled = !draft.isSubmitting,
                isError = check != TipPayoutAmountCheck.VALID,
                errorMessage =
                    when (check) {
                        TipPayoutAmountCheck.VALID -> null
                        TipPayoutAmountCheck.INVALID -> str(TipPayoutKeys.amountInvalid)
                        TipPayoutAmountCheck.EXCEEDS_REMAINING -> str(TipPayoutKeys.amountExceedsRemaining, remaining)
                    },
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done,
            )
            TipPayoutMethodChoice(method = draft.method, onMethodChange = onMethodChange, enabled = !draft.isSubmitting)
        }
    }
}
