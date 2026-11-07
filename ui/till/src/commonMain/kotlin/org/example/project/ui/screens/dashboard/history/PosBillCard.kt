package org.example.project.ui.screens.dashboard.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.presentation.strings.keys.TableOrderHistoryKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.formatReadableDateTime
import org.example.project.core.utils.rememberLanguage
import org.example.project.core.utils.toDecimalOrZero
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.money.Decimal
import org.example.project.ui.components.BillRelationChips

/**
 * Simple bill card for bill history lists.
 *
 * Displays bill number, timestamp, the relation chips ("Storno zu RE-…", "Neubuchung zu RE-…",
 * "Storniert", …), amount, and optional tip. A cancelled original's amount is struck through: it is
 * still a signed receipt and listed, but it no longer stands.
 *
 * @param bill The bill data to display
 * @param onClick Callback when the card is tapped
 * @param isSelected Whether this card is currently selected (highlights border/background)
 * @param modifier Modifier for the root element
 */
@Composable
fun PosBillCard(
    bill: BillDto.BillResponseDto,
    onClick: () -> Unit = {},
    isSelected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val language by rememberLanguage()

    val borderColor =
        if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        }
    val borderWidth = if (isSelected) 2.dp else 1.dp
    val containerColor =
        if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        }

    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(borderWidth, borderColor),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Receipt,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = str(TableOrderHistoryKeys.billNumber, bill.billNumber),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = bill.endTime.formatReadableDateTime(language),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    BillRelationChips(relations = bill.relations, modifier = Modifier.padding(top = 2.dp), isTraining = bill.isTraining)
                }

                val cancelled = isCancelledOriginal(bill)
                Text(
                    text = bill.billAmount.toDecimalOrZero().toFormattedCurrency(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (cancelled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    textDecoration = if (cancelled) TextDecoration.LineThrough else null,
                )
            }

            // Tip line if tips > 0
            if (bill.tips.toDecimalOrZero() > Decimal.ZERO) {
                Text(
                    text = "${str(TableOrderHistoryKeys.tip)}: ${bill.tips.toDecimalOrZero().toFormattedCurrency()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 36.dp),
                )
            }
        }
    }
}

/**
 * A sale that a Storno reversed. The relation flag is read, and the raw `deletingReason` too, so a
 * bill from a server that sends no relations is still marked.
 */
internal fun isCancelledOriginal(bill: BillDto.BillResponseDto): Boolean =
    bill.relations.isCancelled ||
        (bill.deletingReason != null && bill.deletedBillId == null)
