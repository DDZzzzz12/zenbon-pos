package org.example.project.core.domain.dto.table

import kotlinx.serialization.Serializable
import org.example.project.core.domain.enums.room.RoomElementType

/**
 * Data Transfer Objects for Room Element API operations.
 *
 * Provides serializable data classes for API requests and responses related to
 * floor plan structural elements such as walls, doors, windows, counters, and decorations.
 * Separates internal entity representation from external API contracts.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */

/**
 * Container object for all RoomElement-related DTOs.
 *
 * Organizes DTOs by functionality: requests, responses, and utilities
 * to maintain clear separation of concerns and consistent API structure.
 */
object RoomElementDto {
    /**
     * Response DTO for room element data.
     *
     * Used for API responses when returning room element information to clients.
     * Contains all public fields from the entity in a serializable format.
     */
    @Serializable
    data class RoomElementResponseDto(
        /** Room element unique identifier */
        val id: Long,
        /** Type of room element (WALL, DOOR, WINDOW, COUNTER, DECORATION) */
        val elementType: String,
        /** Floor plan X coordinate position */
        val positionX: Float,
        /** Floor plan Y coordinate position */
        val positionY: Float,
        /** Width of element representation in floor plan */
        val width: Float,
        /** Height of element representation in floor plan */
        val height: Float,
        /** Rotation angle in degrees */
        val rotation: Float = 0f,
        /** ID of the location type this element belongs to */
        val locationTypeId: Long,
        /** Additional metadata in JSON format */
        val metadata: String? = null,
    )

    /**
     * Request DTO for creating new room elements.
     *
     * Used for API requests when creating new room element records.
     * Contains required and optional fields for element setup.
     */
    @Serializable
    data class CreateRoomElementRequestDto(
        /** Type of room element (WALL, DOOR, WINDOW, COUNTER, DECORATION) - required */
        val elementType: String,
        /** Floor plan X coordinate position - required */
        val positionX: Float,
        /** Floor plan Y coordinate position - required */
        val positionY: Float,
        /** Width of element representation in floor plan - required */
        val width: Float,
        /** Height of element representation in floor plan - required */
        val height: Float,
        /** Rotation angle in degrees - optional, defaults to 0 */
        val rotation: Float = 0f,
        /** ID of the location type this element belongs to - required */
        val locationTypeId: Long,
        /** Additional metadata in JSON format - optional */
        val metadata: String? = null,
    ) {
        /**
         * Validates the create request data.
         *
         * Ensures all required fields are present and meet format requirements
         * before processing.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            // Validate element type
            require(RoomElementType.entries.any { it.name == elementType }) {
                "Invalid element type: $elementType. Must be one of: ${RoomElementType.entries.joinToString()}"
            }

            // Validate dimensions are positive
            require(width > 0) { "Width must be positive: $width" }
            require(height > 0) { "Height must be positive: $height" }

            // Validate rotation is within valid range
            require(rotation in -360f..360f) { "Rotation must be between -360 and 360 degrees: $rotation" }
        }

        fun getElementType(): RoomElementType = RoomElementType.valueOf(elementType)
    }

    /**
     * Request DTO for updating existing room elements.
     *
     * Used for API requests when updating room element information.
     * All fields are optional to support partial updates.
     */
    @Serializable
    data class UpdateRoomElementRequestDto(
        /** Type of room element */
        val elementType: String? = null,
        /** Floor plan X coordinate position */
        val positionX: Float? = null,
        /** Floor plan Y coordinate position */
        val positionY: Float? = null,
        /** Width of element representation in floor plan */
        val width: Float? = null,
        /** Height of element representation in floor plan */
        val height: Float? = null,
        /** Rotation angle in degrees */
        val rotation: Float? = null,
        /** ID of the location type this element belongs to */
        val locationTypeId: Long? = null,
        /** Additional metadata in JSON format */
        val metadata: String? = null,
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
            // Validate element type if provided
            elementType?.let { type ->
                require(RoomElementType.entries.any { it.name == type }) {
                    "Invalid element type: $type. Must be one of: ${RoomElementType.entries.joinToString()}"
                }
            }

            // Validate dimensions are positive if provided
            width?.let { w ->
                require(w > 0) { "Width must be positive: $w" }
            }
            height?.let { h ->
                require(h > 0) { "Height must be positive: $h" }
            }

            // Validate rotation is within valid range if provided
            rotation?.let { r ->
                require(r in -360f..360f) { "Rotation must be between -360 and 360 degrees: $r" }
            }
        }

        fun getElementType(): RoomElementType? {
            elementType?.let {
                return RoomElementType.valueOf(elementType)
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
            elementType != null ||
                positionX != null ||
                positionY != null ||
                width != null ||
                height != null ||
                rotation != null ||
                locationTypeId != null ||
                metadata != null
    }

    /**
     * Request DTO for updating room element position specifically.
     *
     * Used for dedicated position update operations during floor plan editing,
     * allowing efficient updates without sending all element data.
     */
    @Serializable
    data class UpdateRoomElementPositionRequestDto(
        /** New X coordinate position - required */
        val positionX: Float,
        /** New Y coordinate position - required */
        val positionY: Float,
        /** New rotation angle in degrees - optional */
        val rotation: Float? = null,
    ) {
        /**
         * Validates the position update request.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            // Validate rotation is within valid range if provided
            rotation?.let { r ->
                require(r in -360f..360f) { "Rotation must be between -360 and 360 degrees: $r" }
            }
        }
    }

    /**
     * Request DTO for updating room element dimensions specifically.
     *
     * Used for dedicated dimension update operations during floor plan editing,
     * allowing efficient updates without sending all element data.
     */
    @Serializable
    data class UpdateRoomElementDimensionsRequestDto(
        /** New width - required */
        val width: Float,
        /** New height - required */
        val height: Float,
    ) {
        /**
         * Validates the dimensions update request.
         *
         * @throws IllegalArgumentException if validation fails
         */
        fun validate() {
            require(width > 0) { "Width must be positive: $width" }
            require(height > 0) { "Height must be positive: $height" }
        }
    }

    /**
     * Response DTO for room element lists with filtering information.
     *
     * Provides additional metadata for filtered room element queries
     * to help with pagination and filtering UI components.
     */
    @Serializable
    data class RoomElementListResponseDto(
        /** List of room elements */
        val elements: List<RoomElementResponseDto>,
        /** Total number of elements matching the filter criteria */
        val totalCount: Int,
        /** Number of elements returned in this response */
        val returnedCount: Int,
        /** Applied filters for reference */
        val appliedFilters: FilterInfo,
    )

    /**
     * Filter information for room element queries.
     *
     * Contains metadata about applied filters for transparency
     * and to support client-side filter management.
     */
    @Serializable
    data class FilterInfo(
        /** Element type filter applied (if any) */
        val elementType: String? = null,
        /** Location type filter applied (if any) */
        val locationType: String? = null,
        /** Pagination limit applied */
        val limit: Int? = 0,
        /** Pagination offset applied */
        val offset: Int? = 0,
    )
}
