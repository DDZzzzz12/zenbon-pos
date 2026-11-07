package org.example.project.features.finance.domain.service

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.finance.BookkeepingStartDto

/**
 * The Buchungsbeginn. Throws `PosException`.
 *
 * None of these is a pure read: whichever is called first writes the first day of the current month
 * when no value exists. That includes [cutoff], deliberately — filtering correctness must not depend
 * on which screen the operator happened to open first. [get] is nevertheless idempotent: it returns
 * the same answer however many times it is called. See [BookkeepingStartDto].
 */
interface BookkeepingStartService {
    /** Reports `needsAcknowledgement` until [acknowledge] or [update] confirms the date. */
    suspend fun get(): BookkeepingStartDto.ResponseDto

    /** Rejects anything that is not an ISO `yyyy-MM-dd` date. Acknowledges as a side effect. */
    suspend fun update(startDate: String): BookkeepingStartDto.ResponseDto

    /** Records that a manager has seen the date, and returns it. Dismissing the banner. */
    suspend fun acknowledge(): BookkeepingStartDto.ResponseDto

    /**
     * The cut-off as a timestamp for the two SQL predicates — midnight on the stored date.
     *
     * Creates the default when it is missing, so that reading either work list is enough to
     * establish the cut-off. Returns null only when there is no shop yet, in which case nothing is
     * filtered: an empty system has no history to protect, and filtering everything out would be
     * the worse failure.
     */
    suspend fun cutoff(): LocalDateTime?
}
