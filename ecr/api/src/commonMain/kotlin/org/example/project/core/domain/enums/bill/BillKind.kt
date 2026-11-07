package org.example.project.core.domain.enums.bill

import kotlinx.serialization.Serializable

/**
 * What a bill IS in its family of related bills — derived from the bill's own foreign keys, never
 * stored. Display only: the till and the back office label list rows and detail panels with it.
 *
 * A cancelled original stays a [SALE]; that it was reversed is carried separately
 * (`BillRelationsDto.isCancelled`), because it is still the sale the Storno points at.
 */
@Serializable
enum class BillKind {
    /** An ordinary sale. */
    SALE,

    /** A sale booked by a Zahlartwechsel: it replaces a stornoed original, same items, new tender/tip. */
    REBOOKING,

    /** The negative Beleg that reverses a sale. */
    STORNO,

    /** A payment receipt that clears an open Ausgangsrechnung. */
    RECEIVABLE_CLEARING,
    ;

    companion object {
        /**
         * The kind a bill's own foreign keys make it. A Storno wins over everything, then a
         * Neubuchung ([REBOOKING]), then a receivable clearing, else an ordinary sale — the one
         * precedence the relation chips and the server guards (sales invoice from bill,
         * Bewirtungsbeleg) all share.
         */
        fun of(
            reversesBill: Boolean,
            rebooksBill: Boolean,
            clearsInvoice: Boolean,
        ): BillKind =
            when {
                reversesBill -> STORNO
                rebooksBill -> REBOOKING
                clearsInvoice -> RECEIVABLE_CLEARING
                else -> SALE
            }
    }
}
