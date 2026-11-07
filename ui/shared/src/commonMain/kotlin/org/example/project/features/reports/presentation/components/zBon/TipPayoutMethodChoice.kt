package org.example.project.features.reports.presentation.components.zBon

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import org.example.project.core.domain.enums.zBon.TipPayoutMethod
import org.example.project.core.presentation.strings.keys.TipPayoutKeys
import org.example.project.core.presentation.strings.str

/**
 * F59, option C: "über Bank/Lohn ausgezahlt" instead of cash. Checked, the payout moves no cash and
 * books no Kassenbuch entry — it only settles the open amount; the hint says who books it instead.
 * Shared by the closing dialog's rows and the payout dialog after the Z-Bon.
 */
@Composable
fun TipPayoutMethodChoice(
    method: TipPayoutMethod,
    onMethodChange: (TipPayoutMethod) -> Unit,
    enabled: Boolean = true,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // raw Material 3 by design: no Pos checkbox component exists; every till checkbox uses this
            Checkbox(
                checked = method == TipPayoutMethod.BANK_PAYROLL,
                onCheckedChange = { onMethodChange(if (it) TipPayoutMethod.BANK_PAYROLL else TipPayoutMethod.CASH) },
                enabled = enabled,
            )
            Text(text = str(TipPayoutKeys.methodBankPayroll), style = MaterialTheme.typography.bodyMedium)
        }
        if (method == TipPayoutMethod.BANK_PAYROLL) {
            Text(text = str(TipPayoutKeys.bankPayrollHint), style = MaterialTheme.typography.bodySmall)
        }
    }
}
