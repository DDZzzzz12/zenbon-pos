package org.example.project.core.domain.dto.finance

import kotlinx.serialization.Serializable

/**
 * The Buchungsbeginn — the date open-item tracking starts from.
 *
 * [ResponseDto.needsAcknowledgement] is true for as long as no manager has confirmed the date, and
 * it is what drives the banner. It replaced a one-shot `wasDefaulted` flag, which was true on
 * exactly the response that wrote the default: that made the banner unrecoverable after a reload,
 * and unreachable entirely once reading an invoice list became enough to create the default.
 * Acknowledging it — explicitly, or by choosing a date — clears it for good.
 */
object BookkeepingStartDto {
    @Serializable
    data class ResponseDto(
        /** ISO `yyyy-MM-dd`. */
        val startDate: String,
        val needsAcknowledgement: Boolean = false,
    )

    @Serializable
    data class UpdateRequestDto(
        val startDate: String,
    )
}
