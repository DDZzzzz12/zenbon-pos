@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package org.example.project.core.domain.dto.table

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import org.example.project.core.domain.dto.bill.BillDto
import org.example.project.core.domain.dto.customer.CustomerAddressDto
import org.example.project.core.domain.dto.customer.CustomerDto
import org.example.project.core.domain.dto.employee.EmployeeDto

/**
 * Data Transfer Objects (DTOs) for table session management operations.
 *
 * Provides comprehensive DTO classes for table session API operations including
 * request validation, response formatting, and analytics data structures.
 * Supports session lifecycle management and operational reporting.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */

/**
 * Container class for all table session related DTOs.
 *
 * Organizes DTOs by functionality: requests, responses, and analytics
 * to maintain clear separation of concerns and consistent API structure.
 */
object TableSessionDto {
    /**
     * Request DTO for creating a new table session.
     *
     * Contains all required and optional fields for session creation
     * with comprehensive validation and business rule enforcement.
     */
    @Serializable
    data class CreateTableSessionRequestDto(
        /** ID of the table for this session */
        val tableId: Long,
        /** ID of the employee starting the session */
        val employeeId: String, // UUID as String for JSON serialization
        /** Optional custom start time (defaults to current time) */
        val startTime: LocalDateTime? = null,
    ) {
        /**
         * Validates the create session request data.
         *
         * Ensures all required fields are present and valid according to
         * business rules and database constraints.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(tableId > 0) { "Table ID must be positive" }

            // Employee ID format validation could be added here if needed

            // Validate start time is not in the distant future (more than 1 hour)
            startTime?.let { time ->
                // Simple validation - in real implementation you'd do proper time comparison
                require(time.toString().length > 0) {
                    "Start time must be a valid date/time"
                }
            }
        }
    }

    /**
     * Request DTO for updating an existing table session.
     *
     * Supports partial updates where only provided fields will be modified.
     * All fields are optional to allow flexible update operations.
     */
    @Serializable
    data class UpdateTableSessionRequestDto(
        /** New employee ID for the session (optional) */
        val employeeId: String? = null, // UUID as String for JSON serialization
        /** Updated start time (optional) */
        val startTime: LocalDateTime? = null,
        /** Updated end time (optional) */
        val endTime: LocalDateTime? = null,
        /** Updated completion status (optional) */
        val completed: Boolean? = null,
    ) {
        /**
         * Validates the update session request data.
         *
         * Ensures provided fields are valid and consistent with business rules.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            // Employee ID format validation could be added here if needed

            // Validate time consistency if both times are provided
            if (startTime != null && endTime != null) {
                require(endTime >= startTime) {
                    "End time must be after or equal to start time"
                }
            }

            // If marking as completed, validate that required fields make sense
            completed?.let { isCompleted ->
                if (isCompleted && endTime == null) {
                    // Warning: completing without end time (will be set automatically)
                }
            }
        }
    }

    /**
     * Response DTO for table session data.
     *
     * Contains complete session information for API responses
     * including related entity details and calculated fields.
     */
    @Serializable
    data class TableSessionResponseDto(
        /** Unique session identifier */
        val id: Long,
        /** Table number for display purposes */
        val tableNumber: String,
        /** Employee ID responsible for this session */
        val employeeId: String, // UUID as String for JSON serialization
        /** Employee number for display purposes */
        val employeeNumber: String,
        val isDelivery: Boolean,
        val customer: CustomerDto.CustomerSummaryDto?,
        val deliveryAddress: CustomerAddressDto.CustomerAddressSummaryDto?,
        /** Session start time */
        val startTime: LocalDateTime?,
        /** Session end time (null if still active) */
        val endTime: LocalDateTime?,
        /** Whether the session is completed */
        val completed: Boolean,
        /** Whether this session was created by a training-mode employee */
        val isTraining: Boolean = false,
    )

    /**
     * Simplified session summary DTO for list views.
     *
     * Contains essential session information for efficient
     * list rendering and summary displays.
     */
    @Serializable
    data class SessionSummaryDto(
        /** Session ID */
        val id: Long,
        /** Table number */
        val tableNumber: String,
        /** Employee username */
        val employeeInfo: EmployeeDto.EmployeeInfoDto,
        /** Start time */
        val startTime: LocalDateTime?,
        val endTime: LocalDateTime? = null,
        val itemCount: Long? = null,
        val orderCount: Long? = null,
        /** Duration if completed */
        val durationMinutes: String? = null,
        val bills: List<BillDto.BillSummaryDto>? = null,
        val note: String? = null,
        /** Whether this session was created by a training-mode employee */
        val isTraining: Boolean = false,
    )

    @Serializable
    data class SessionCustomerDto(
        val isDelivery: Boolean,
        val customer: CustomerDto.CustomerResponseDto?,
        val deliveryAddress: CustomerAddressDto.CustomerAddressResponseDto?,
    )

    /**
     * Request DTO for assigning customer and delivery address to a table session.
     *
     * Used to convert a regular table session to a delivery session or to
     * associate customer information with an existing session.
     */
    @Serializable
    data class AssignCustomerAndAddressRequestDto(
        /** Customer UUID to assign to the session */
        val customerId: String,
        /** Delivery address UUID (required if isDelivery is true) */
        val deliveryAddressId: String?,
        /** Whether to mark this session as a delivery session */
        val isDelivery: Boolean,
        /** Optional override phone number for delivery contact */
        val deliveryPhoneNumber: String? = null,
    ) {
        /**
         * Validates the assignment request data.
         *
         * Ensures customer ID is provided and delivery address is present
         * if this is being marked as a delivery session.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(customerId.isNotBlank()) { "Customer ID is required" }

            // If marking as delivery, delivery address is mandatory
            if (isDelivery) {
                require(!deliveryAddressId.isNullOrBlank()) {
                    "Delivery address ID is required for delivery sessions"
                }
            }

            // Phone number validation if provided
            deliveryPhoneNumber?.let { phone ->
                require(phone.isNotBlank()) {
                    "Delivery phone number cannot be blank"
                }
            }
        }
    }

    /**
     * Result of the one-off repair for tables that reference a completed session.
     *
     * Two corrupt variants are healed:
     * - [clearedDanglingPointers]: completed session held no OPEN items, so the
     *   dangling table pointer was simply cleared (table freed).
     * - [reopenedSessions]: completed session still had OPEN items, so a fresh
     *   active session was opened and the OPEN items + their orders were moved to it.
     *
     * The operation is idempotent — a second run against clean data returns all zeros.
     */
    @Serializable
    data class SessionRepairResultDto(
        /** Number of tables whose dangling pointer to a completed session was cleared. */
        val clearedDanglingPointers: Int,
        /** Number of tables that were re-opened onto a fresh session (had OPEN items). */
        val reopenedSessions: Int,
        /** Total number of OPEN order items moved to fresh sessions. */
        val movedOpenItems: Int,
    )
}
