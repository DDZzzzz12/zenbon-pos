package org.example.project.core.data.remote

import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto

/**
 * What `POST /api/v1/kassenbuch` did with a new entry.
 *
 * A Beleg date past the shop's limit is a backdating override: a caller holding
 * `CASHBOOK_BACKDATE` books it at once, anyone else gets **202** and a manager-approval request —
 * nothing is booked yet. The two must never look alike on the client: "booked" and "waiting for a
 * manager" are different answers to give an operator (the same lesson as [ApprovalOutcome]).
 */
sealed interface KassenbuchCreateOutcome {
    /** 201: the entries were booked. */
    data class Created(
        val group: KassenbuchDto.CreateKassenbuchEntryGroupResponseDto,
    ) : KassenbuchCreateOutcome

    /** 202: an approval request was raised; the entry is booked only once a manager approves. */
    data object AwaitingApproval : KassenbuchCreateOutcome
}
