package org.example.project.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.enums.bill.BillKind
import org.example.project.core.domain.enums.bill.StornoReason
import org.example.project.core.presentation.strings.keys.BillReceiptKeys
import org.example.project.core.presentation.strings.str

/**
 * The chips a bill LIST row carries: what the bill is to its family, in one or two short labels.
 *
 * - a Neubuchung (Zahlartwechsel): "Neubuchung zu RE-…"
 * - a sale settling items a "re-open the table" Storno handed back: "Neubuchung zu RE-…[, RE-…]",
 *   with "(teilweise)" when it also holds new items
 * - a Storno: "Storno zu RE-…", or the neutral "Storno (Zahlartwechsel) zu RE-…" for a payment change
 * - a receivable clearing: "Ausgleich zu RE-…" (only when the invoice came from a bill)
 * - a cancelled original: "Storniert", or "Storniert (Zahlartwechsel)" when a Neubuchung replaced it —
 *   not warning-coloured, since nothing was refunded
 */
fun BillDto.BillRelationsDto.listLabels(): List<BillRelationLabel> =
    buildList {
        when (kind) {
            BillKind.REBOOKING ->
                rebooksBill?.let { add(BillRelationLabel(BillReceiptKeys.relationRebookOf, it)) }
            BillKind.STORNO -> reversesBill?.let { add(stornoLabel(it)) }
            BillKind.RECEIVABLE_CLEARING ->
                clearsBill?.let { add(BillRelationLabel(BillReceiptKeys.relationClearingOf, it)) }
            BillKind.SALE -> Unit
        }
        rebillLabel()?.let(::add)
        when {
            !isCancelled -> Unit
            rebookedBy != null -> add(BillRelationLabel(BillReceiptKeys.relationCancelledRebook))
            else -> add(BillRelationLabel(BillReceiptKeys.relationCancelled, isWarning = true))
        }
    }

/**
 * The status chips a bill's DETAIL panel leads with: "Training" for a Schulungsbeleg, then
 * "Storniert" — or the neutral "Storniert (Zahlartwechsel)" when a Neubuchung replaced it. admin20
 * NOTE-B: the panel showed a training receipt exactly like a sale; the list rows already badged it.
 */
fun BillDto.BillResponseDto.detailStatusLabels(): List<BillRelationLabel> =
    buildList {
        if (isTraining) add(BillRelationLabel(BillReceiptKeys.trainingBadge))
        when {
            !relations.isCancelled -> Unit
            relations.rebookedBy != null -> add(BillRelationLabel(BillReceiptKeys.relationCancelledRebook))
            else -> add(BillRelationLabel(BillReceiptKeys.relationCancelled, isWarning = true))
        }
    }

/**
 * The tappable links a bill's DETAIL panel offers, each opening the linked bill in place:
 * "Neubuchung zu RE-…", "Storno zu RE-…", "Ausgleich zu RE-…", "Storniert durch RE-…" and
 * "Neu gebucht als RE-…".
 */
fun BillDto.BillRelationsDto.detailLinks(): List<BillRelationLabel> =
    buildList {
        rebooksBill?.let { add(BillRelationLabel(BillReceiptKeys.relationRebookOf, it)) }
        // One tappable link per re-billed original, each opening it.
        rebilledFromBills.forEach { add(BillRelationLabel(rebillKey(), it)) }
        reversesBill?.let { add(stornoLabel(it)) }
        clearsBill?.let { add(BillRelationLabel(BillReceiptKeys.relationClearingOf, it)) }
        reversedBy?.let { add(BillRelationLabel(BillReceiptKeys.relationReversedByLink, it, isWarning = true)) }
        rebookedBy?.let { add(BillRelationLabel(BillReceiptKeys.relationRebookedByLink, it)) }
    }

/**
 * The list chip of a re-bill: every stornoed original its handed-back items came from, in one chip.
 * Null on a Storno (its items are the original's) and when nothing was handed back.
 */
private fun BillDto.BillRelationsDto.rebillLabel(): BillRelationLabel? {
    if (kind == BillKind.STORNO) return null
    val first = rebilledFromBills.firstOrNull() ?: return null
    return BillRelationLabel(rebillKey(), first, alsoTargets = rebilledFromBills.drop(1))
}

private fun BillDto.BillRelationsDto.rebillKey() = if (rebillIsPartial) BillReceiptKeys.relationRebookOfPartial else BillReceiptKeys.relationRebookOf

/**
 * A Storno's link to the bill it reverses. The Storno half of a Zahlartwechsel refunded nothing — the
 * sale was booked again at once — so it is named as such and not warning-coloured.
 */
private fun BillDto.BillRelationsDto.stornoLabel(original: BillDto.BillLinkDto): BillRelationLabel =
    if (stornoReason == StornoReason.ZAHLARTWECHSEL) {
        BillRelationLabel(BillReceiptKeys.relationStornoRebookOf, original)
    } else {
        BillRelationLabel(BillReceiptKeys.relationStornoOf, original, isWarning = true)
    }

/** Resolves a [BillRelationLabel] to its localized text, the linked bill number(s) filling `{0}`. */
@Composable
fun BillRelationLabel.text(): String = argument?.let { str(key, it) } ?: str(key)

/**
 * A receipt-list table cell: "Training" first for a Schulungsbeleg, then the [listLabels] relations
 * as one line ("Training · Storno zu RE-4"). Empty for a plain real sale. emu20 NOTE-t: the Z-Bon list now carries
 * the closing's training receipts, and they must not read like sales.
 */
@Composable
fun BillDto.BillResponseDto.listSummaryText(): String {
    val training = if (isTraining) listOf(str(BillReceiptKeys.trainingBadge)) else emptyList()
    return (training + relations.listLabels().map { it.text() }).joinToString(" · ")
}

/**
 * The relation chips of a list row ([listLabels]). Status only — the row itself opens the bill.
 * A Schulungsbeleg ([isTraining]) leads with a "Training" chip. Renders nothing for an ordinary sale.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BillRelationChips(
    relations: BillDto.BillRelationsDto,
    modifier: Modifier = Modifier,
    isTraining: Boolean = false,
) {
    val labels = relations.listLabels()
    if (labels.isEmpty() && !isTraining) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (isTraining) {
            PosStatusChip(
                text = str(BillReceiptKeys.trainingBadge),
                backgroundColor = MaterialTheme.colorScheme.tertiaryContainer,
                textColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
        labels.forEach { label ->
            PosStatusChip(
                text = label.text(),
                backgroundColor =
                    if (label.isWarning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                textColor =
                    if (label.isWarning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
