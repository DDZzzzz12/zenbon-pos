package org.example.project.core.data.remote

import org.example.project.core.domain.dto.finance.BookkeepingStartDto

/**
 * The Buchungsbeginn — the date open-item tracking starts from. Every endpoint is MANAGER-only
 * server-side.
 *
 * [get] is **not** a pure read: the first call in the system's lifetime writes the self-dated
 * default. It is nonetheless idempotent — it reports the same answer, including
 * `needsAcknowledgement`, however often it is called. The banner is therefore driven by a durable
 * flag rather than by a one-shot response: a reload at the wrong moment can no longer lose it, and
 * neither can a list request that happens to create the default first.
 */
interface BookkeepingStartApiClient {
    suspend fun get(): BookkeepingStartDto.ResponseDto

    /**
     * Records that a manager has seen the date, so the banner stops appearing.
     *
     * This is what dismissing the banner calls; without it the dismissal would last only until the
     * next screen was opened.
     */
    suspend fun acknowledge(): BookkeepingStartDto.ResponseDto

    /**
     * Rejected with 400 when [startDate] is not an ISO `yyyy-MM-dd` date.
     *
     * It acknowledges as a side effect — choosing a date means the operator has seen it — so no
     * separate [acknowledge] call is needed after saving.
     */
    suspend fun update(startDate: String): BookkeepingStartDto.ResponseDto
}
