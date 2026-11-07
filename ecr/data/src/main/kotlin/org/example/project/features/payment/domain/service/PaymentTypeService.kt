package org.example.project.features.payment.domain.service

import org.example.project.core.domain.dto.payment.PaymentTypeDto

/**
 * Service interface for PaymentType business operations.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors;
 * the global StatusPages handler converts them to application/problem+json.
 */
interface PaymentTypeService {
    /**
     * Retrieves all payment types with optional filtering.
     *
     * @param includeInactive Whether to include inactive payment types in results
     */
    suspend fun getAllPaymentTypes(includeInactive: Boolean = false): PaymentTypeDto.PaymentTypeListResponseDto

    /**
     * Retrieves a payment type by its unique identifier.
     */
    suspend fun getPaymentTypeById(id: Long): PaymentTypeDto.PaymentTypeResponseDto

    /**
     * Retrieves a payment type by its name.
     */
    suspend fun getPaymentTypeByName(name: String): PaymentTypeDto.PaymentTypeResponseDto

    /**
     * Creates a new payment type with business validation.
     *
     * @param employeeId ID of the employee performing the operation (for entry logging)
     * @param request The payment type creation request with validation
     */
    suspend fun createPaymentType(
        employeeId: String,
        request: PaymentTypeDto.CreatePaymentTypeRequestDto,
    ): PaymentTypeDto.PaymentTypeResponseDto

    /**
     * Updates an existing payment type with business validation.
     */
    suspend fun updatePaymentType(
        employeeId: String,
        id: Long,
        request: PaymentTypeDto.UpdatePaymentTypeRequestDto,
    ): PaymentTypeDto.PaymentTypeResponseDto

    /**
     * Deletes a payment type after business rule validation.
     */
    suspend fun deletePaymentType(
        employeeId: String,
        id: Long,
    )

    /**
     * Retrieves active payment types for dropdown/selection components.
     */
    suspend fun getActivePaymentTypes(): List<PaymentTypeDto.PaymentTypeSummaryDto>

    /**
     * Validates if a payment type name is unique.
     *
     * @param name The name to validate for uniqueness
     * @param excludeId Optional ID to exclude from uniqueness check
     * @return true if the name is available
     */
    suspend fun isNameAvailable(
        name: String,
        excludeId: Long? = null,
    ): Boolean

    /**
     * Retrieves payment type statistics and counts.
     */
    suspend fun getPaymentTypeStatistics(): PaymentTypeDto.PaymentTypeStatisticsDto

    /**
     * Retrieves payment types updated after a specific timestamp (incremental sync).
     */
    suspend fun getPaymentTypesUpdatedAfter(lastSyncTime: String): List<PaymentTypeDto.PaymentTypeResponseDto>
}
