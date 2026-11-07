package org.example.project.features.reports.presentation.components.zBon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.zBon.TipPayoutMethod
import org.example.project.core.presentation.strings.keys.TipPayoutKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.reports.presentation.state.ClosingTipPayoutRow
import org.example.project.features.reports.presentation.state.TipPayoutAmountCheck
import org.example.project.money.Decimal
import org.example.project.money.DecimalSeparator
import org.example.project.money.MoneyFormat
import org.example.project.money.format
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.formatMoneyForDisplay

/**
 * The "Trinkgeld auszahlen" section of the closing dialog (F58): one row per employee with employee
 * tips in the period being closed — "Mia: 3,00 € (2,00 bar + 1,00 Karte)" — a checkbox (checked by
 * default) and the amount to hand out, prefilled with the whole tip and capped at it. F59: a second
 * checkbox settles the row "über Bank/Lohn" instead — no cash leaves the drawer. With
 * [methodChoiceEnabled] false (no signing till) the choice is not offered: the rows are bank/payroll.
 */
@Composable
fun ClosingTipPayoutSection(
    rows: List<ClosingTipPayoutRow>,
    currencyCode: String,
    onCheckedChange: (employeeId: String, checked: Boolean) -> Unit,
    onAmountChange: (employeeId: String, text: String) -> Unit,
    onMethodChange: (employeeId: String, method: TipPayoutMethod) -> Unit = { _, _ -> },
    methodChoiceEnabled: Boolean = true,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = str(TipPayoutKeys.sectionTitle), style = MaterialTheme.typography.titleSmall)
        Text(text = str(TipPayoutKeys.closingSectionHint), style = MaterialTheme.typography.bodySmall)
        rows.forEach { row ->
            ClosingTipPayoutRowItem(
                row = row,
                currencyCode = currencyCode,
                onCheckedChange = { onCheckedChange(row.employeeId, it) },
                onAmountChange = { onAmountChange(row.employeeId, it) },
                onMethodChange = { onMethodChange(row.employeeId, it) },
                methodChoiceEnabled = methodChoiceEnabled,
            )
        }
    }
}

@Composable
private fun ClosingTipPayoutRowItem(
    row: ClosingTipPayoutRow,
    currencyCode: String,
    onCheckedChange: (Boolean) -> Unit,
    onAmountChange: (String) -> Unit,
    onMethodChange: (TipPayoutMethod) -> Unit,
    methodChoiceEnabled: Boolean,
) {
    val total = formatMoneyForDisplay(row.total, currencyCode)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // raw Material 3 by design: no Pos checkbox component exists; every till checkbox uses this
        Checkbox(checked = row.checked, onCheckedChange = onCheckedChange)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = str(TipPayoutKeys.closingRow, row.employeeName, total, splitPart(row.cash), splitPart(row.card)),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (row.checked) {
                // F64: the field owns the text the operator is typing. It used to show `row.amount`,
                // the ViewModel's echo of the previous keystroke, which reaches this composition only
                // after the StateFlow collector runs (PosReportsScreen's collectAsState). Every frame in
                // between recomposed the text field with the PREVIOUS text, and the field pushed that
                // stale text back to the input method — the field and the IME fought over the content
                // on every keystroke. That is the likeliest cause of the till freezing ("App reagiert
                // nicht") on a loaded host while the operator lowered "3,00" to "1"; the loop itself
                // was never reproduced, so this removes the race rather than a proven hang. The
                // ViewModel is still told every change;
                // it simply no longer drives the text back into the field mid-edit. Keyed on the
                // employee, so a different row never inherits this one's text; unchecking disposes it.
                var text by remember(row.employeeId) { mutableStateOf(row.amount) }
                val check = TipPayoutAmountCheck.of(text, row.total)
                PosTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        onAmountChange(it)
                    },
                    label = str(TipPayoutKeys.closingAmountLabel),
                    isError = check != TipPayoutAmountCheck.VALID,
                    errorMessage =
                        when (check) {
                            TipPayoutAmountCheck.VALID -> null
                            // F65: an empty or zero amount on a ticked row blocks "Bestätigen" like
                            // any invalid one, but says what to do about it.
                            TipPayoutAmountCheck.INVALID ->
                                if (ClosingTipPayoutRow.isMissingAmount(text)) {
                                    str(TipPayoutKeys.closingAmountMissing)
                                } else {
                                    str(TipPayoutKeys.amountInvalid)
                                }
                            TipPayoutAmountCheck.EXCEEDS_REMAINING -> str(TipPayoutKeys.amountExceedsRemaining, total)
                        },
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Next,
                )
                if (methodChoiceEnabled) {
                    TipPayoutMethodChoice(method = row.method, onMethodChange = onMethodChange)
                } else {
                    // No signing till: cash is not offered, the row is bank/payroll. Say who books it.
                    Text(text = str(TipPayoutKeys.bankPayrollHint), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** A split part as the row shows it: "2,00" — comma, no symbol, like the amount field. */
private fun splitPart(value: String): String = (Decimal.parseOrNull(value) ?: Decimal.ZERO).format(MoneyFormat(separator = DecimalSeparator.COMMA))
