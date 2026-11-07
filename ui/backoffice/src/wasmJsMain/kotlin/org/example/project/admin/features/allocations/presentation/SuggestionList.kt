package org.example.project.admin.features.allocations.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.allocation.SuggestionDto
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosMoneyText
import org.example.project.ui.components.PosStatusChip

/**
 * What the ladder thinks this payment settles.
 *
 * With **one** suggestion it is shown as an answer with its own *Übernehmen*. With **several**, all
 * are shown and none is privileged: no pre-selection, no highlight, no focused button, however much
 * stronger the first is. Spec §5 — a wrong pre-filled answer costs more than no answer, because
 * *Übernehmen* becomes reflexive. Every row is therefore rendered by the same [SuggestionRow] call
 * with no index and no position-dependent styling; adding either would break the rule.
 *
 * An empty list renders nothing at all, not an empty state: most payments match nothing provable,
 * and a permanent "no suggestions" line would train the eye to skip the area entirely.
 *
 * Nothing here is applied on its own, at any strength — a proposal is only ever booked by the
 * button.
 */
@Composable
fun SuggestionList(
    items: List<SuggestionDto.ItemDto>,
    isSaving: Boolean,
    onAccept: (SuggestionDto.ItemDto) -> Unit,
) {
    if (items.isEmpty()) return

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = str(AdminStringKeys.AllocationsSuggestionsTitle),
            style = MaterialTheme.typography.labelLarge,
        )
        items.forEach { item ->
            SuggestionRow(item = item, isSaving = isSaving, onAccept = onAccept)
        }
    }
}

private val SUGGESTION_WIDE_MIN = 560.dp
private val SUGGESTION_AMOUNT_WIDTH = 110.dp
private val SUGGESTION_ACTION_WIDTH = 148.dp

@Composable
private fun SuggestionRow(
    item: SuggestionDto.ItemDto,
    isSaving: Boolean,
    onAccept: (SuggestionDto.ItemDto) -> Unit,
) {
    // Why it was suggested — an operator asking that deserves an answer they can report.
    val signal = localized(signalLabelKey(item.signal), item.signal)
    val strength = localized(strengthLabelKey(item.strength), item.strength)
    val accept: @Composable (Modifier) -> Unit = { buttonModifier ->
        PosButton(
            text = str(AdminStringKeys.AllocationsSuggestionAccept),
            onClick = { onAccept(item) },
            enabled = !isSaving,
            modifier = buttonModifier,
        )
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= SUGGESTION_WIDE_MIN) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text = item.label, style = MaterialTheme.typography.bodyMedium)
                    SignalLine(signal, strength, item.strength)
                }
                PosMoneyText(
                    amount = item.amount,
                    currencyCode = item.currency,
                    modifier = Modifier.width(SUGGESTION_AMOUNT_WIDTH),
                    textAlign = TextAlign.End,
                )
                accept(Modifier.width(SUGGESTION_ACTION_WIDTH))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = item.label, style = MaterialTheme.typography.bodyMedium)
                SignalLine(signal, strength, item.strength)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PosMoneyText(amount = item.amount, currencyCode = item.currency)
                    Spacer(Modifier.weight(1f))
                    accept(Modifier)
                }
            }
        }
    }
}

/** The matching signal as small text, with the match strength as a chip rather than text after a bullet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SignalLine(
    signal: String,
    strengthText: String,
    strengthRaw: String,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = signal, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val strong = strengthRaw == "EXACT" || strengthRaw == "STRONG"
        PosStatusChip(
            text = strengthText,
            backgroundColor = if (strong) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
            textColor = if (strong) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** Falls back to the server's raw constant, so an unmapped signal still says *something* usable. */
@Composable
private fun localized(
    key: StringKey?,
    raw: String,
): String = key?.let { str(it) } ?: raw

private fun signalLabelKey(signal: String): StringKey? =
    when (signal) {
        "STRUCTURED_REFERENCE" -> AdminStringKeys.AllocationsSignalStructuredReference
        "REMITTANCE_TEXT" -> AdminStringKeys.AllocationsSignalRemittanceText
        "MANDATE_OR_CREDITOR" -> AdminStringKeys.AllocationsSignalMandateOrCreditor
        "IBAN_SINGLE_OPEN" -> AdminStringKeys.AllocationsSignalIbanSingleOpen
        "CONTRACT_WINDOW" -> AdminStringKeys.AllocationsSignalContractWindow
        "SUPPLIER_NAME_AMOUNT_DATE" -> AdminStringKeys.AllocationsSignalSupplierNameAmountDate
        "DEBTOR_NAME_AMOUNT_DATE" -> AdminStringKeys.AllocationsSignalDebtorNameAmountDate
        "SUBSET_SUM" -> AdminStringKeys.AllocationsSignalSubsetSum
        else -> null
    }

private fun strengthLabelKey(strength: String): StringKey? =
    when (strength) {
        "EXACT" -> AdminStringKeys.AllocationsStrengthExact
        "STRONG" -> AdminStringKeys.AllocationsStrengthStrong
        "MEDIUM" -> AdminStringKeys.AllocationsStrengthMedium
        else -> null
    }
