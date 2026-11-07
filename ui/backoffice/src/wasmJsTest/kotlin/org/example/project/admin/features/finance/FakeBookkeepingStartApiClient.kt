package org.example.project.admin.features.finance

import org.example.project.core.data.remote.BookkeepingStartApiClient
import org.example.project.core.domain.dto.finance.BookkeepingStartDto

/**
 * Replays a canned Buchungsbeginn and records every call, for both screens that host the banner.
 *
 * [getCalls], [acknowledgeCalls] and [updated] are what prove the banner's three halves: reading is
 * idempotent, dismissing reaches the server exactly once, and saving reaches it exactly once
 * *without* a second acknowledgement — the PUT acknowledges by itself.
 *
 * It mirrors the server: acknowledging and updating both clear [needsAcknowledgement] durably, so a
 * later [get] no longer raises the banner.
 */
class FakeBookkeepingStartApiClient : BookkeepingStartApiClient {
    var startDate: String = "2026-08-01"
    var needsAcknowledgement: Boolean = false
    var failWith: Throwable? = null

    var getCalls = 0
        private set
    var acknowledgeCalls = 0
        private set
    val updated = mutableListOf<String>()

    override suspend fun get(): BookkeepingStartDto.ResponseDto {
        getCalls++
        failWith?.let { throw it }
        return response()
    }

    override suspend fun acknowledge(): BookkeepingStartDto.ResponseDto {
        acknowledgeCalls++
        failWith?.let { throw it }
        needsAcknowledgement = false
        return response()
    }

    override suspend fun update(startDate: String): BookkeepingStartDto.ResponseDto {
        updated += startDate
        failWith?.let { throw it }
        this.startDate = startDate
        this.needsAcknowledgement = false
        return response()
    }

    private fun response() =
        BookkeepingStartDto.ResponseDto(
            startDate = startDate,
            needsAcknowledgement = needsAcknowledgement,
        )
}
