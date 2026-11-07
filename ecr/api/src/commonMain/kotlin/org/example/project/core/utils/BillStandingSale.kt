package org.example.project.core.utils

import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.enums.bill.BillKind

/*
 * Whether a bill is a STANDING SALE — a sale that has not been reversed. Only such a bill may become
 * an Ausgangsrechnung or get a Bewirtungsbeleg: a Neubuchung IS a sale (same items, corrected tender),
 * a Storno and its cancelled original no longer stand, and a receivable clearing is a payment
 * receipt, not a sale.
 *
 * One predicate for both levels of the guard (CLAUDE.md rule 8): the till hides the actions with it,
 * and the server refuses them with `BILL_NOT_A_STANDING_SALE` on the same answer.
 */

/** A bill of [kind] stands as a sale when it is an ordinary sale or a Neubuchung that was not cancelled. */
fun isStandingSale(
    kind: BillKind,
    isCancelled: Boolean,
): Boolean = (kind == BillKind.SALE || kind == BillKind.REBOOKING) && !isCancelled

/**
 * [isStandingSale] for a bill on screen. The raw Storno links are checked too, so a bill from a
 * server that sends no relations (every field defaults to an ordinary sale) is still not offered as
 * a sale once it was stornoed.
 */
fun BillDto.BillResponseDto.isStandingSale(): Boolean = isStandingSale(relations.kind, relations.isCancelled) && deletingReason == null && deletedBillId == null
