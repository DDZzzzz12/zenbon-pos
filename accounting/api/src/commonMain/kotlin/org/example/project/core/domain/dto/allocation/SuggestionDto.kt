package org.example.project.core.domain.dto.allocation

import kotlinx.serialization.Serializable

/**
 * What one payment probably settles — the match ladder's answer, on the wire.
 *
 * **An empty [ResponseDto.items] is a normal answer, not a failure.** Most bank transactions match
 * nothing we can prove, and an ineligible one (a reversal, or an entry on a statement not yet
 * assigned to one of our accounts) also answers with an empty list. The endpoint returns 200 in both
 * cases: an error status would make the client show a failure toast for the commonest case in the
 * system, and an operator who is trained to dismiss the toast will dismiss the real ones too.
 *
 * **Nothing here is applied automatically.** A suggestion is offered and a human accepts it, so the
 * list is ordered strongest-first and ties are never collapsed into a default.
 *
 * Every amount is a decimal string; money never crosses the wire as a JSON number.
 */
object SuggestionDto {
    /** [total] is simply how many suggestions there are — the list is not paged. */
    @Serializable
    data class ResponseDto(
        val items: List<ItemDto> = emptyList(),
        val total: Int = 0,
    )

    /**
     * One thing the payment might be settling.
     *
     * The ids are one per kind rather than one polymorphic id, matching `payment_allocation` and
     * [AllocationDto.CreateRequestDto], so an accepted suggestion can be handed straight back as an
     * allocation without a translation step.
     *
     * @property targetKind `INVOICE` or `CONTRACT_INSTALMENT`, which says which id below is set.
     * @property invoiceIds several ids when one payment settles a *set* of invoices — the ladder's
     *   subset-sum signal. Empty on a contract instalment.
     * @property signal which rule fired, named as in the spec's ladder so an operator (and a bug
     *   report) can say *why* something was suggested.
     * @property strength `EXACT`, `STRONG` or `MEDIUM` — the order the list is already in.
     * @property amount what accepting this would allocate: the payment, or the target's residual
     *   when that is smaller.
     * @property label a human-readable name for the target — the invoice numbers, or the contract's
     *   name and the instalment's due date. Sent rather than re-derived on the client, which would
     *   otherwise need the invoice and contract rows just to render a line of text.
     */
    @Serializable
    data class ItemDto(
        val targetKind: String,
        val signal: String,
        val strength: String,
        val amount: String,
        val currency: String,
        val label: String,
        val invoiceIds: List<Long> = emptyList(),
        /** Several ids when one CREDIT payment settles a set of STANDALONE invoices (subset-sum). */
        val salesInvoiceIds: List<Long> = emptyList(),
        val contractInstalmentId: Long? = null,
    )
}
