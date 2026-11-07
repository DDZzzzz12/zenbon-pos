package org.example.project.features.reports.presentation.components.zBon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.TipPayoutKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.features.reports.presentation.state.TipReversalDraft
import org.example.project.ui.components.PosDangerButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.PosTextField

/**
 * Confirms the storno of one booked tip payout. A reason is required (it goes on the Eigenbeleg);
 * confirming is disabled without one or while the request is in flight.
 */
@Composable
fun TipReversalDialog(
    draft: TipReversalDraft,
    onReasonChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    PosDialog(
        onDismiss = onDismiss,
        title = str(TipPayoutKeys.reverseDialogTitle),
        dismissOnClickOutside = !draft.isSubmitting,
        footer = {
            PosTextButton(
                text = str(CommonKeys.cancel),
                onClick = onDismiss,
                enabled = !draft.isSubmitting,
            )
            PosDangerButton(
                text = str(TipPayoutKeys.reverseConfirm),
                onClick = onConfirm,
                enabled = draft.canConfirm,
            )
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text =
                    str(
                        TipPayoutKeys.reverseDialogMessage,
                        draft.payout.amount.toFormattedCurrency(),
                        draft.employeeName,
                        // F59: a bank/payroll settlement has no Beleg — name the method instead.
                        draft.payout.belegNr ?: str(TipPayoutKeys.methodBankPayroll),
                    ),
            )
            PosTextField(
                value = draft.reason,
                onValueChange = onReasonChange,
                label = str(TipPayoutKeys.reasonLabel),
                enabled = !draft.isSubmitting,
                isError = draft.reason.isBlank(),
                errorMessage = if (draft.reason.isBlank()) str(TipPayoutKeys.reasonRequired) else null,
                imeAction = ImeAction.Done,
            )
        }
    }
}
