package org.example.project.core.domain.dto.cardTerminal

import kotlinx.serialization.Serializable

/**
 * Container object for CardPayment SSE/WebSocket event DTOs.
 *
 * Organizes event types emitted during a card payment flow,
 * including intermediate status updates, success, bill creation,
 * and failure events.
 */
object CardPaymentEventDto {
    @Serializable
    data class IntermediateStatusEvent(
        val type: String = "intermediate_status",
        val code: Int,
        val message: String,
    )

    @Serializable
    data class CardPaymentSuccessEvent(
        val type: String = "card_payment_success",
        val cardTransactionId: Long,
    )

    @Serializable
    data class BillCreatedEvent(
        val type: String = "bill_created",
        val billId: Long,
        val billNumber: String,
    )

    @Serializable
    data class FailedEvent(
        val type: String = "failed",
        val error: String,
        val phase: String,
    )
}
