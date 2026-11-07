/**
 * Customer entity to DTO mapping functions.
 *
 * Provides conversion methods for Customer entities to their corresponding DTOs
 * for API responses and data transfer operations.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.customer.mapper

import org.example.project.core.domain.dto.customer.CustomerDto
import org.example.project.features.customer.domain.models.Customer

/**
 * Converts Customer entity to CustomerResponseDto.
 *
 * Maps all customer fields including personal information, contact details,
 * privacy consents, and activity tracking to the API response format.
 *
 * @return CustomerResponseDto with all customer data
 */
fun Customer.toResponse(): CustomerDto.CustomerResponseDto =
    CustomerDto.CustomerResponseDto(
        id = this.id.value.toString(),
        customerNumber = this.customerNumber,
        firstName = this.firstName,
        lastName = this.lastName,
        fullName = this.fullName,
        email = this.email,
        phone = this.phone,
        notes = this.notes,
        marketingConsent = this.marketingConsent,
        dataProcessingConsent = this.dataProcessingConsent,
        lastPurchaseAt = this.lastPurchaseAt,
        isActive = this.isActive,
        createdAt = this.createdAt,
        updatedAt = this.updatedAt,
    )

/**
 * Extension function to create CustomerResponseDto.fromEntity() functionality.
 *
 * Provides static factory method on the companion object for consistent
 * entity-to-DTO conversion across the service layer.
 *
 * @param entity Customer entity to convert
 * @return CustomerResponseDto instance
 */
fun CustomerDto.CustomerResponseDto.Companion.fromEntity(entity: Customer): CustomerDto.CustomerResponseDto = entity.toResponse()

/**
 * Converts Customer entity to CustomerSummaryDto.
 *
 * Provides lightweight version with essential customer information for list views,
 * reducing payload size for bulk operations.
 *
 * @return CustomerSummaryDto with essential customer data
 */
fun Customer.toSummary(): CustomerDto.CustomerSummaryDto =
    CustomerDto.CustomerSummaryDto(
        id = this.id.value.toString(),
        customerNumber = this.customerNumber,
        fullName = this.fullName,
        email = this.email,
        phone = this.phone,
        isActive = this.isActive,
    )
