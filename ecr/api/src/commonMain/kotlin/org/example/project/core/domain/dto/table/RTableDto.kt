package org.example.project.core.domain.dto.table

import kotlinx.serialization.Serializable
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.utils.requireValidReason

/**
 * Data Transfer Objects for Restaurant Table API operations.
 *
 * Provides serializable data classes for API requests and responses,
 * separating internal entity representation from external API contracts.
 * Includes validation and conversion utilities for table management.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */

/**
 * Container object for all RTable-related DTOs.
 *
 * Organizes DTOs by functionality: requests, responses, and utilities
 * to maintain clear separation of concerns and consistent API structure.
 */
object RTableDto {
    /**
     * Response DTO for restaurant table data.
     *
     * Used for API responses when returning table information to clients.
     * Contains all public fields from the entity in a serializable format.
     */
    @Serializable
    data class RTableResponseDto(
        /** Table unique identifier */
        val id: Long,
        /** Table number or identifier used for customer reference */
        val tableNumber: String,
        /** Number of seats available at this table */
        val seats: Int?,
        /** ID of the location type */
        val locationTypeId: Long,
        /** Localized name of the location type */
        val locationTypeName: String,
        /** English name of the location type */
        val locationTypeEnglishName: String,
        /** Chinese name of the location type */
        val locationTypeChineseName: String,
        /** Whether this is a virtual location type */
        val isVirtual: Boolean,
        /** Whether this is a takeaway location type */
        val isTakeaway: Boolean,
        /** Current operational status of the table (FREE, OCCUPIED, RESERVED) */
        val tableStatus: String,
        /** ID of current active session (if any) */
        val currentSessionId: Long?,
        /** Whether the table has an active session */
        val hasActiveSession: Boolean,
        /** Floor plan X coordinate position */
        val positionX: Float?,
        /** Floor plan Y coordinate position */
        val positionY: Float?,
        /** Rotation angle in degrees */
        val rotation: Float?,
        /** Width of table representation in floor plan */
        val width: Float? = 100f,
        /** Height of table representation in floor plan */
        val height: Float? = 100f,
        /** Employee number of the employee currently serving this table (from active session) */
        val currentEmployeeNumber: String? = null,
        /** Whether the current session is a training session */
        val isTraining: Boolean = false,
        /** Whether the table is active (not soft-deleted) */
        val isActive: Boolean = true,
        /** Assignment priority for auto-assign (lower = assigned first, 0 = default) */
        val assignmentPriority: Int = 0,
        /** Open (unpaid) total of the current session as a 2-dp plain string (e.g. "48.50"); null when free or <= 0. */
        val openAmount: String? = null,
        /** System-provisioned table (the virtual VOUCHER table): read-only for operators. */
        val isSystem: Boolean = false,
    )

    @Serializable
    data class TablesWithSessionDto(
        val tableId: Long,
        val session: TableSessionDto.SessionSummaryDto,
    )

    /**
     * Request DTO for creating new restaurant tables.
     *
     * Used for API requests when creating new table records.
     * Contains required and optional fields for table setup.
     */
    @Serializable
    data class CreateRTableRequestDto(
        /** Table number or identifier - must be unique - required */
        val tableNumber: String,
        /** Number of seats available at this table - optional */
        val seats: Int? = null,
        /** ID of the location type - required */
        val locationTypeId: Long,
        /** Floor plan X coordinate position - optional, defaults to 0 */
        val positionX: Float? = null,
        /** Floor plan Y coordinate position - optional, defaults to 0 */
        val positionY: Float? = null,
        /** Rotation angle in degrees - optional, defaults to 0 */
        val rotation: Float? = null,
        /** Width of table representation in floor plan - optional, defaults to 100 */
        val width: Float? = null,
        /** Height of table representation in floor plan - optional, defaults to 100 */
        val height: Float? = null,
        /** Assignment priority for auto-assign (lower = assigned first, 0 = default) */
        val assignmentPriority: Int = 0,
    ) {
        /**
         * Validates the create request data.
         *
         * Ensures all required fields are present and optional fields
         * meet format requirements before processing.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(tableNumber.isNotBlank()) { "Table number is required and cannot be blank" }
            require(tableNumber.length <= 10) { "Table number must not exceed 10 characters" }

            // Validate seats is positive if provided
            seats?.let { seatCount ->
                require(seatCount > 0) { "Number of seats must be positive: $seatCount" }
                require(seatCount <= 50) { "Number of seats cannot exceed 50: $seatCount" }
            }
        }
    }

    /**
     * Request DTO for updating existing restaurant tables.
     *
     * Used for API requests when updating table information.
     * All fields are optional to support partial updates.
     */
    @Serializable
    data class UpdateRTableRequestDto(
        /** Table number or identifier - must be unique if changed */
        val tableNumber: String? = null,
        /** Number of seats available at this table */
        val seats: Int? = null,
        /** ID of the location type */
        val locationTypeId: Long? = null,
        /** Current operational status of the table */
        val tableStatus: String? = null,
        /** Floor plan X coordinate position */
        val positionX: Float? = null,
        /** Floor plan Y coordinate position */
        val positionY: Float? = null,
        /** Rotation angle in degrees */
        val rotation: Float? = null,
        /** Width of table representation in floor plan */
        val width: Float? = null,
        /** Height of table representation in floor plan */
        val height: Float? = null,
        /** Assignment priority for auto-assign (lower = assigned first, 0 = default) */
        val assignmentPriority: Int? = null,
    ) {
        /**
         * Validates the update request data.
         *
         * Ensures provided fields meet format requirements.
         * Empty fields are ignored for partial updates.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            // Validate table number if provided
            tableNumber?.let { number ->
                require(number.isNotBlank()) { "Table number cannot be blank if provided" }
                require(number.length <= 10) { "Table number must not exceed 10 characters" }
            }

            // Validate seats if provided
            seats?.let { seatCount ->
                require(seatCount > 0) { "Number of seats must be positive: $seatCount" }
                require(seatCount <= 50) { "Number of seats cannot exceed 50: $seatCount" }
            }

            // Basic table type and status validation could be added here if needed
        }

        fun getTableStatus(): TableStatus? {
            tableStatus?.let {
                return TableStatus.valueOf(tableStatus)
            }
            return null
        }

        /**
         * Checks if the update request contains any actual changes.
         *
         * Determines whether the request has any non-null fields
         * to avoid unnecessary database operations.
         *
         * @return true if at least one field is provided for update
         */
        fun hasChanges(): Boolean =
            tableNumber != null ||
                seats != null ||
                locationTypeId != null ||
                tableStatus != null ||
                positionX != null ||
                positionY != null ||
                rotation != null ||
                width != null ||
                height != null
    }

    /**
     * Request DTO for updating table status specifically.
     *
     * Used for dedicated table status change operations
     * for operational management and seating coordination.
     */
    @Serializable
    data class UpdateTableStatusRequestDto(
        /** New status to assign to the table */
        val tableStatus: String,
    ) {
        /**
         * Validates the status update request.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            // Basic table status validation could be added here if needed
            require(TableStatus.entries.any { it.name == tableStatus })
        }

        fun getTableStatus(): TableStatus {
            tableStatus.let {
                return TableStatus.valueOf(tableStatus)
            }
        }
    }

    /**
     * Request DTO for batch updating table positions.
     *
     * Allows efficient updating of multiple table positions in a single
     * API call, useful for floor plan editor drag operations.
     */
    @Serializable
    data class BatchTablePositionUpdateDto(
        /** List of table position updates to apply */
        val updates: List<TablePositionUpdate>,
    ) {
        /**
         * Validates the batch update request.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(updates.isNotEmpty()) { "Batch update must contain at least one update" }
            require(updates.size <= 100) { "Batch update cannot exceed 100 tables at once" }
            updates.forEach { it.validate() }
        }
    }

    /**
     * Individual table position update within a batch operation.
     *
     * Contains the table ID and new position/dimension values.
     */
    @Serializable
    data class TablePositionUpdate(
        /** ID of the table to update */
        val tableId: Long,
        /** New X coordinate position */
        val positionX: Float,
        /** New Y coordinate position */
        val positionY: Float,
        /** New rotation angle in degrees */
        val rotation: Float,
        /** New width */
        val width: Float,
        /** New height */
        val height: Float,
    ) {
        /**
         * Validates the position update data.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(tableId > 0) { "Table ID must be positive: $tableId" }
            require(width > 0) { "Width must be positive: $width" }
            require(height > 0) { "Height must be positive: $height" }
            require(rotation in -360f..360f) { "Rotation must be between -360 and 360 degrees: $rotation" }
        }
    }

    /**
     * Response DTO for batch position update operation.
     *
     * Contains statistics about the batch update operation including
     * how many tables were successfully updated.
     */
    @Serializable
    data class BatchUpdatePositionsResponseDto(
        /** Whether the operation completed successfully */
        val success: Boolean,
        /** Number of tables actually updated */
        val updatedCount: Int,
        /** Number of table updates requested */
        val requestedCount: Int,
    )

    /**
     * Response DTO for table lists with filtering information.
     *
     * Provides additional metadata for filtered table queries
     * to help with pagination and filtering UI components.
     */
    @Serializable
    data class RTableListResponseDto(
        /** List of tables */
        val tables: List<RTableResponseDto>,
        /** Total number of tables matching the filter criteria */
        val totalCount: Int,
        /** Number of tables returned in this response */
        val returnedCount: Int,
        /** Applied filters for reference */
        val appliedFilters: FilterInfo,
    )

    /**
     * Filter information for table queries.
     *
     * Contains metadata about applied filters for transparency
     * and to support client-side filter management.
     */
    @Serializable
    data class FilterInfo(
        /** Status filter applied (if any) */
        val status: String? = null,
        /** Type filter applied (if any) */
        val type: String? = null,
        /** Pagination limit applied */
        val limit: Int? = 0,
        /** Pagination offset applied */
        val offset: Int? = 0,
    )

    /**
     * Request DTO for transferring a table session.
     *
     * Used when moving a table session (and all associated order items)
     * from one table to another. Handles two scenarios:
     * 1. Target table has active session: Transfer items to existing session
     * 2. Target table has no session: Move entire session to target table
     */
    @Serializable
    data class TransferTableRequestDto(
        /** Target table ID to transfer to */
        val targetTableId: Long,
        val targetEmployeeNumber: String,
        /** Required reason for the transfer (for audit trail); nullable on the wire so an old client gets a 400 */
        val reason: String? = null,
    ) {
        /**
         * Validates the transfer request data.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(targetTableId > 0) { "Target table ID must be positive" }
            reason.requireValidReason()
        }
    }

    /**
     * Response DTO for table transfer operation.
     *
     * Provides details about the transfer operation outcome including
     * whether items were transferred or session was moved.
     */
    @Serializable
    data class TransferTableResponseDto(
        /** Whether the operation completed successfully */
        val success: Boolean,
        /** Type of transfer performed (ITEM_TRANSFER or SESSION_MOVE) */
        val transferType: String,
        /** Source table ID */
        val sourceTableId: Long,
        /** Target table ID */
        val targetTableId: Long,
        /** Number of items affected (for ITEM_TRANSFER type) */
        val itemsTransferred: Int? = null,
        /** Session ID that was moved (for SESSION_MOVE type) */
        val sessionId: Long? = null,
        /** Descriptive message about the operation */
        val message: String,
    ) {
        companion object {
            /**
             * Creates a response for item transfer scenario.
             */
            fun forItemTransfer(
                sourceTableId: Long,
                targetTableId: Long,
                itemCount: Int,
            ): TransferTableResponseDto =
                TransferTableResponseDto(
                    success = true,
                    transferType = "ITEM_TRANSFER",
                    sourceTableId = sourceTableId,
                    targetTableId = targetTableId,
                    itemsTransferred = itemCount,
                    message = "Successfully transferred $itemCount item(s) from table $sourceTableId to table $targetTableId",
                )

            /**
             * Creates a response for session move scenario.
             */
            fun forSessionMove(
                sourceTableId: Long,
                targetTableId: Long,
                sessionId: Long,
            ): TransferTableResponseDto =
                TransferTableResponseDto(
                    success = true,
                    transferType = "SESSION_MOVE",
                    sourceTableId = sourceTableId,
                    targetTableId = targetTableId,
                    sessionId = sessionId,
                    message = "Successfully moved session from table $sourceTableId to table $targetTableId",
                )
        }
    }

    @Serializable
    data class CombinablePairDto(
        val tableAId: Long,
        val tableBId: Long,
    )

    @Serializable
    data class SetCombinablePairsRequestDto(
        val pairs: List<CombinablePairDto>,
    )
}
