package org.example.project.ui.components

import org.example.project.core.domain.dto.bill.BillDto

/**
 * The back stack of a bill detail panel whose related-bill links were followed in place
 * (correction → original → Storno …). Immutable; a host view model keeps one in its state next to
 * the bill it shows and feeds [backTo] to `PosBillDetailPanel`.
 *
 * Following a link to a bill already on the trail collapses the trail back to it instead of growing
 * it, so RE-0 → RE-C0 → "Korrektur zu RE-0" returns to RE-0 with no back target left, rather than
 * stacking RE-0 twice.
 */
data class BillDetailTrail(
    val previous: List<BillDto.BillResponseDto> = emptyList(),
) {
    /** The bill "back" returns to, or null when the panel shows the bill the operator opened. */
    val backTo: BillDto.BillLinkDto?
        get() = previous.lastOrNull()?.let { BillDto.BillLinkDto(it.billId, it.billNumber) }

    /** The trail after leaving [current] for the bill with [targetId]. */
    fun follow(
        current: BillDto.BillResponseDto,
        targetId: Long,
    ): BillDetailTrail {
        val onTrail = previous.indexOfFirst { it.billId == targetId }
        return if (onTrail >= 0) BillDetailTrail(previous.take(onTrail)) else BillDetailTrail(previous + current)
    }

    /** The trail with any entry for [bill]'s id replaced by [bill] — e.g. an original re-read after a Zahlartwechsel. */
    fun refreshed(bill: BillDto.BillResponseDto): BillDetailTrail = BillDetailTrail(previous.map { if (it.billId == bill.billId) bill else it })

    /** The bill to go back to and the trail left behind; null bill when there is nothing to go back to. */
    fun back(): Pair<BillDto.BillResponseDto?, BillDetailTrail> = previous.lastOrNull() to BillDetailTrail(previous.dropLast(1))
}
