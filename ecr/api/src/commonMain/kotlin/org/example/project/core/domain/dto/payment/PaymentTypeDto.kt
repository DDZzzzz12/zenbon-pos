/**
 * Container object for all PaymentType-related DTOs.
 *
 * Organizes DTOs by functionality: requests, responses, and analytics
 * to maintain clear separation of concerns and consistent API structure.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.dto.payment

import kotlinx.serialization.Serializable
import org.example.project.core.utils.DsfinvkFieldLimits

/**
 * Container object for all PaymentType-related DTOs.
 *
 * Organizes payment type DTOs by functionality including requests,
 * responses, and validation with proper serialization support for
 * cross-platform compatibility.
 */
object PaymentTypeDto {
    /**
     * Response DTO for PaymentType data.
     *
     * Contains all payment type information for API responses including
     * metadata for client-side processing and display.
     */
    @Serializable
    data class PaymentTypeResponseDto(
        val id: Long,
        val name: String,
        val en: String? = null,
        val ch: String? = null,
        val isCash: Boolean = false,
        val isActive: Boolean,
        val isVoucher: Boolean = false,
        /** 4c: a tender settled "auf Rechnung" — opens a receivable rather than taking money. */
        val createsReceivable: Boolean = false,
        val updatedAt: String, // ISO string format for cross-platform compatibility
    )

    /**
     * Request DTO for creating a new PaymentType.
     *
     * Contains all required fields for payment type creation with
     * proper validation and business rule enforcement.
     */
    @Serializable
    data class CreatePaymentTypeRequestDto(
        val name: String,
        val en: String? = null,
        val ch: String? = null,
        val isCash: Boolean = false,
        val isActive: Boolean = true,
        /**
         * 4c-3: a tender that means "the customer will pay later" — settling with it opens a
         * receivable rather than taking money. Master data; each payment freezes its own copy.
         */
        val createsReceivable: Boolean = false,
    ) {
        /**
         * Validates the create request data.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(name.isNotBlank()) { "Payment type name is required" }
            require(name.length <= 100) { "Payment type name must be 100 characters or less" }
            require(name.trim().length >= 2) { "Payment type name must be at least 2 characters long" }
            // M1: exported as datapayment.csv / payment.csv ZAHLART_NAME (max 60).
            DsfinvkFieldLimits.requireMaxLength(name, DsfinvkFieldLimits.ZAHLART_NAME, "Payment type name (ZAHLART_NAME)")

            en?.let {
                require(it.length <= 100) { "English name must be 100 characters or less" }
            }

            ch?.let {
                require(it.length <= 100) { "Chinese name must be 100 characters or less" }
            }
        }
    }

    /**
     * Request DTO for updating an existing PaymentType.
     *
     * Contains all updatable fields with optional values for partial updates.
     * Only non-null fields will be updated in the entity.
     */
    @Serializable
    data class UpdatePaymentTypeRequestDto(
        val name: String? = null,
        val en: String? = null,
        val ch: String? = null,
        val isCash: Boolean? = null,
        val isActive: Boolean? = null,
        /** 4c: null leaves the receivable flag unchanged; true/false sets it (only future settlements). */
        val createsReceivable: Boolean? = null,
    ) {
        /**
         * Validates the update request data.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            name?.let {
                require(it.isNotBlank()) { "Payment type name cannot be blank" }
                require(it.length <= 100) { "Payment type name must be 100 characters or less" }
                require(it.trim().length >= 2) { "Payment type name must be at least 2 characters long" }
                DsfinvkFieldLimits.requireMaxLength(it, DsfinvkFieldLimits.ZAHLART_NAME, "Payment type name (ZAHLART_NAME)")
            }

            en?.let {
                require(it.length <= 100) { "English name must be 100 characters or less" }
            }

            ch?.let {
                require(it.length <= 100) { "Chinese name must be 100 characters or less" }
            }
        }

        /**
         * Checks if this update request has any fields to update.
         *
         * @return true if at least one field is non-null
         */
        fun hasUpdates(): Boolean = name != null || en != null || ch != null || isCash != null || isActive != null || createsReceivable != null
    }

    /**
     * Response DTO for payment type list operations.
     *
     * Contains paginated list of payment types with metadata for
     * client-side pagination and filtering.
     */
    @Serializable
    data class PaymentTypeListResponseDto(
        val paymentTypes: List<PaymentTypeResponseDto>,
        val total: Int,
        val activeCount: Int,
        val inactiveCount: Int,
    )

    /**
     * Response DTO for payment type summary information.
     *
     * Provides lightweight payment type information for dropdown
     * menus and selection components.
     */
    @Serializable
    data class PaymentTypeSummaryDto(
        val id: Long,
        val name: String,
        val isActive: Boolean,
    )

    /**
     * Response DTO for payment type statistics.
     *
     * Provides statistical information about payment types including
     * counts and percentages for analytics and reporting purposes.
     */
    @Serializable
    data class PaymentTypeStatisticsDto(
        val totalCount: Long,
        val activeCount: Long,
        val inactiveCount: Long,
        val activePercentage: Double,
    )
}
