package org.example.project.features.reports.presentation.components.zBon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.presentation.strings.keys.TipPayoutKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.money.Decimal
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.PosTonalButton
import org.example.project.ui.theme.PosSpacing

/**
 * "Trinkgeld auszahlen" on a Z-Bon's detail: per employee with employee tips in that closing, the
 * tips, what has been paid out and what is still open, with a payout action per open row.
 *
 * Renders nothing for a closing the server reports no positions for (an older closing, a list read,
 * an older server). [onPayOut] null hides every action — the operator lacks `CASHBOOK_WRITE`; a row
 * with nothing open shows "paid out in full" instead of the action. The server repeats both guards.
 */
@Composable
fun TipPayoutSection(
    positions: List<ZBonDto.EmployeeTipPayoutStatusDto>,
    onPayOut: ((ZBonDto.EmployeeTipPayoutStatusDto) -> Unit)?,
    modifier: Modifier = Modifier,
    /** Opens the storno dialog for one booked payout; null hides "Stornieren" (no `CASHBOOK_WRITE`). */
    onReverse: ((ZBonDto.EmployeeTipPayoutStatusDto, ZBonDto.TipPayoutRecordDto) -> Unit)? = null,
) {
    if (positions.isEmpty()) return
    PosCard(
        modifier = modifier.fillMaxWidth(),
        variant = PosCardVariant.Tonal,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        body {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(PosSpacing.sm),
            ) {
                Text(
                    text = str(TipPayoutKeys.sectionTitle),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = str(TipPayoutKeys.sectionHint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                positions.forEach { position ->
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 2.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    TipPayoutRow(position, onPayOut)
                    position.payouts.forEach { record ->
                        TipPayoutRecordRow(record, onReverse?.let { reverse -> { reverse(position, record) } })
                    }
                }
            }
        }
    }
}

@Composable
private fun TipPayoutRow(
    position: ZBonDto.EmployeeTipPayoutStatusDto,
    onPayOut: ((ZBonDto.EmployeeTipPayoutStatusDto) -> Unit)?,
) {
    val isOpen = (Decimal.parseOrNull(position.remaining) ?: Decimal.ZERO) > Decimal.ZERO
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = position.employeeName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text =
                    "${str(TipPayoutKeys.tipTotal)} ${position.tipTotal.toFormattedCurrency()} · " +
                        "${str(TipPayoutKeys.paidOut)} ${position.paidOut.toFormattedCurrency()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text =
                    if (isOpen) {
                        "${str(TipPayoutKeys.remaining)} ${position.remaining.toFormattedCurrency()}"
                    } else {
                        str(TipPayoutKeys.fullyPaid)
                    },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isOpen) FontWeight.Bold else FontWeight.Normal,
            )
        }
        if (isOpen && onPayOut != null) {
            PosTonalButton(
                text = str(TipPayoutKeys.payOutAction),
                onClick = { onPayOut(position) },
            )
        }
    }
}

/**
 * One booked payout under its employee: amount and Beleg, then "Stornieren" — or "Storniert" once
 * reversed, because a payout can be reversed only once. [onReverse] null hides the action.
 */
@Composable
private fun TipPayoutRecordRow(
    record: ZBonDto.TipPayoutRecordDto,
    onReverse: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            // F59: a bank/payroll settlement has no Kassenbuch Beleg to name.
            text =
                record.belegNr?.let { str(TipPayoutKeys.payoutLine, record.amount.toFormattedCurrency(), it) }
                    ?: str(TipPayoutKeys.bankPayrollRecord, record.amount.toFormattedCurrency()),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        when {
            record.reversed ->
                Text(
                    text = str(TipPayoutKeys.reversedLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            onReverse != null -> PosTextButton(text = str(TipPayoutKeys.reverseAction), onClick = onReverse)
        }
    }
}
