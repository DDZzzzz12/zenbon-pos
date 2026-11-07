/**
 * PaymentType data model.
 *
 * Represents a payment type entity for local storage and offline functionality,
 * following clean architecture principles with no external dependencies.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.data.models

import kotlinx.serialization.Serializable
import org.example.project.core.domain.dto.payment.PaymentTypeDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.utils.localizedPaymentName
import org.example.project.database.Local_payment_types

/**
 * PaymentType data model for local storage.
 *
 * Represents different payment methods available in the system
 * such as cash, credit card, digital payments, etc.
 */
@Serializable
data class PaymentType(
    val id: Long,
    val name: String,
    val enName: String? = null,
    val chName: String? = null,
    val isActive: Boolean,
    /** 4c: a tender settled "auf Rechnung" — the till keeps it out of the settle-receivable picker. */
    val createsReceivable: Boolean = false,
) {
    companion object {
        /**
         * Converts SQLDelight database row to PaymentType model.
         *
         * @param dbPaymentType SQLDelight database row
         * @return PaymentType domain model
         */
        fun fromDatabase(dbPaymentType: Local_payment_types): PaymentType =
            PaymentType(
                id = dbPaymentType.id,
                name = dbPaymentType.name,
                chName = dbPaymentType.ch_name,
                enName = dbPaymentType.en_name,
                isActive = dbPaymentType.is_active == 1L,
                createsReceivable = dbPaymentType.creates_receivable == 1L,
            )

        /**
         * Converts PaymentTypeDto.PaymentTypeResponseDto to PaymentType model.
         *
         * Used when syncing data from server API to local storage.
         *
         * @param dto API response DTO
         * @return PaymentType domain model
         */
        fun fromDto(dto: PaymentTypeDto.PaymentTypeResponseDto): PaymentType =
            PaymentType(
                id = dto.id,
                name = dto.name,
                enName = dto.en,
                chName = dto.ch,
                isActive = dto.isActive,
                createsReceivable = dto.createsReceivable,
            )
    }

    /** The name in [language]; a missing or blank translation falls back to the German [name]. */
    fun getName(language: Language): String = localizedPaymentName(language, german = name, english = enName, chinese = chName)
}
