package org.example.project.features.matching.domain.service

import org.example.project.core.domain.dto.allocation.SuggestionDto

/**
 * What one payment probably settles, as the HTTP layer sees it.
 *
 * Read-only: this feature suggests and never applies. Accepting a suggestion is an allocation, and
 * goes through `AllocationService` like any other, so nothing here writes a row.
 */
interface MatchSuggestionService {
    /**
     * The suggestions for one bank transaction, strongest first.
     *
     * **An empty list is a normal answer.** Most payments match nothing provable, and an ineligible
     * transaction — a reversal, or one whose statement is not yet assigned to an account — answers
     * the same way rather than by failing. Throws `PosException` (404) only when no transaction
     * carries [transactionId]: that is the caller asking about something that does not exist, which
     * is a different statement from "nothing matches".
     */
    suspend fun suggestionsFor(transactionId: Long): SuggestionDto.ResponseDto
}
