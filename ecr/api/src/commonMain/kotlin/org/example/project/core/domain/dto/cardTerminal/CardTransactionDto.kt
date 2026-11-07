package org.example.project.core.domain.dto.cardTerminal

import kotlinx.serialization.Serializable

/**
 * Container object for CardTransaction-related DTOs.
 *
 * Organizes DTOs for card payment transaction data
 * returned from the ZVT terminal integration.
 */
object CardTransactionDto {
    @Serializable
    data class CardTransactionResponseDto(
        val id: Long,
        val paymentId: Long?,
        val cardTerminalId: Long,
        val traceNumber: String,
        val receiptNumber: String?,
        val amountCents: Long,
        val currency: String,
        val cardType: Int?,
        val cardTypeName: String?,
        val panLast4: String?,
        val expiryDate: String?,
        val aid: String?,
        val paymentType: String,
        val resultCode: Int,
        val receiptTextMerchant: String?,
        val receiptTextCustomer: String?,
        val transactionTime: String, // LocalDateTime serialized as String
        val createdAt: String, // LocalDateTime serialized as String
    )
}
