package org.example.project.core.domain.dto.payment

import kotlinx.serialization.Serializable
import org.example.project.core.domain.enums.bill.PaymentStatus

@Suppress("UnsafeOptInUsageError")
object PaymentDto {
    @Serializable
    data class PaymentRequest(
        val amount: String,
        val paymentTypeId: Long,
    )

    @Serializable
    data class PaymentSummary(
        val amount: String,
        val paymentCount: Int = 1,
        val paymentTypeEN: String? = null,
        val paymentTypeDE: String,
        val paymentTypeCH: String? = null,
    )

    /**
     * Response DTO for payment data.
     *
     * Used for API responses when returning payment information.
     * Contains payment details including amount, type, and timing.
     */
    @Serializable
    data class PaymentResponse(
        val id: Long,
        val amount: String, // BigDecimal as String
        val paymentTypeId: Long,
        val paymentTypeName: String,
        val paymentTypeEN: String? = null,
        val paymentTypeCH: String? = null,
        val billId: Long,
        val createdAt: String, // LocalDateTime as String
        /** REFUNDED once a Storno reversed it; only COMPLETED payments make up a bill's standing tender. */
        val paymentStatus: PaymentStatus = PaymentStatus.COMPLETED,
        /**
         * The leg was tendered "auf Rechnung" — the frozen snapshot on the payment, not the type's live
         * flag. Drives the till's tip-on-account check (`isTipOnAccount`); defaults so an older server
         * reads as "not on account" and the server's own refusal remains the enforcement.
         */
        val createsReceivable: Boolean = false,
    ) {
        companion object
    }

    /**
     * Get default payment types for initial system setup.
     *
     * Creates a predefined list of common payment types with German names,
     * English, and Chinese translations for multi-language support.
     *
     * @return List of CreatePaymentTypeRequestDto for default payment types
     */
    fun getDefaultPaymentTypes(): List<PaymentTypeDto.CreatePaymentTypeRequestDto> =
        listOf(
            PaymentTypeDto.CreatePaymentTypeRequestDto(
                name = "BAR",
                en = "CASH",
                ch = "现金",
                isActive = true,
                isCash = true,
            ),
            PaymentTypeDto.CreatePaymentTypeRequestDto(
                name = "KREDITKARTE",
                en = "CREDIT-CARD",
                ch = "信用卡",
                isActive = true,
            ),
            PaymentTypeDto.CreatePaymentTypeRequestDto(
                name = "EC-KARTE",
                en = "EC-CARD",
                ch = "借记卡",
                isActive = true,
            ),
            PaymentTypeDto.CreatePaymentTypeRequestDto(
                name = "ÜBERWEISUNG",
                en = "BANK-TRANSFER",
                ch = "银行转账",
                isActive = true,
                // 4c-3: a bank transfer is settled "auf Rechnung" — the money is owed, not received.
                // Both ways to CLEAR the receivable now exist (bank reconciliation, till clearing), so
                // this is finally safe to enable (spec §2.1).
                createsReceivable = true,
            ),
        )
}
