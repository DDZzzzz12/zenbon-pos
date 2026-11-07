package org.example.project.features.reports.presentation.components.zBon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.TipPayoutKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.reports.presentation.state.ZBonClosingDraft
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.formatMoneyForDisplay

/**
 * F59, option A — shown in "Z-Bon erstellen" only while the checked CASH payouts exceed the drawer:
 * the shortfall, "Fehlbetrag aus <Kassenbuch> einlegen" with a choice among the shop's other active
 * cash books that hold enough, and the amount, prefilled with the exact shortfall. The server books it
 * as a Geldtransit into the Ladenkasse inside the closing, before the payouts. With no eligible book
 * the closing stays blocked until less is paid in cash.
 */
@Composable
fun ClosingTopUpSection(
    draft: ZBonClosingDraft,
    currencyCode: String,
    onSourceSelect: (cashBookId: Long) -> Unit,
    onAmountChange: (String) -> Unit,
) {
    val shortfall = draft.shortfall ?: return
    val shortfallText = formatMoneyForDisplay(shortfall.toPlainString(), currencyCode)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = str(TipPayoutKeys.shortfall, shortfallText),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        val sources = draft.eligibleTopUpSources
        if (sources.isEmpty()) {
            Text(text = str(TipPayoutKeys.topUpNoSource), style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        val chosen = draft.topUpSource
        Text(
            text = str(TipPayoutKeys.topUpFrom, chosen?.name ?: sources.first().name),
            style = MaterialTheme.typography.titleSmall,
        )
        sources.forEach { source ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // raw Material 3 by design, like the till's checkboxes: no Pos radio component exists
                RadioButton(
                    selected = source.cashBookId == chosen?.cashBookId,
                    onClick = { onSourceSelect(source.cashBookId) },
                )
                Text(
                    text = str(TipPayoutKeys.topUpSource, source.name, formatMoneyForDisplay(source.balance, currencyCode)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        PosTextField(
            value = draft.topUpText,
            onValueChange = onAmountChange,
            label = str(TipPayoutKeys.topUpAmountLabel),
            isError = draft.topUpTooLow,
            errorMessage = if (draft.topUpTooLow) str(TipPayoutKeys.topUpTooLow, shortfallText) else null,
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Next,
        )
    }
}
