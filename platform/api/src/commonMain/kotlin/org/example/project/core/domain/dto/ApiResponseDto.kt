/**
 * Data Transfer Objects for standardized API responses.
 *
 * Provides consistent response structures for all API endpoints
 * ensuring type safety and proper JSON serialization across the application.
 * These DTOs are shared between client and server.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.dto

import kotlinx.serialization.Serializable

/**
 * Container object for all API response-related DTOs.
 *
 * Organizes response DTOs by functionality to maintain clear separation of concerns
 * and consistent API structure across all endpoints.
 */
object ApiResponseDto {
    /**
     * Generic success response with optional data payload.
     *
     * @property success Always true for this response type
     * @property message Human-readable success message
     * @property data Optional data payload of any type
     */
    @Serializable
    data class SuccessResponse<T>(
        val success: Boolean = true,
        val message: String,
        val data: T? = null,
    )

    /**
     * Generic error response for API operations.
     *
     * @property success Always false for this response type
     * @property error Primary error message
     * @property errorCode Optional error code for client-side handling
     * @property details Additional error details or validation failures
     */
    @Serializable
    data class ErrorResponse(
        val success: Boolean = false,
        val error: String,
        val errorCode: String? = null,
        val details: List<String> = emptyList(),
    )

    /**
     * Bulk operation result response.
     *
     * Used for operations that affect multiple entities (e.g., bulk updates, deletions).
     *
     * @property success Whether the bulk operation succeeded
     * @property message Result message
     * @property successCount Number of successfully processed items
     * @property failedCount Number of failed items
     * @property totalCount Total number of items attempted
     * @property errors List of specific error messages for failed items
     */
    @Serializable
    data class BulkOperationResponse(
        val success: Boolean,
        val message: String,
        val successCount: Int,
        val failedCount: Int = 0,
        val totalCount: Int,
        val errors: List<String> = emptyList(),
    )

    /**
     * Paginated response wrapper.
     *
     * @property success Whether the request succeeded
     * @property data List of items for current page
     * @property pagination Pagination metadata
     */
    @Serializable
    data class PaginatedResponse<T>(
        val success: Boolean = true,
        val data: List<T>,
        val pagination: PaginationInfo,
    )

    /**
     * Pagination metadata.
     *
     * @property currentPage Current page number (0-indexed)
     * @property pageSize Number of items per page
     * @property totalItems Total number of items across all pages
     * @property totalPages Total number of pages
     * @property hasNext Whether there is a next page
     * @property hasPrevious Whether there is a previous page
     */
    @Serializable
    data class PaginationInfo(
        val currentPage: Int,
        val pageSize: Int,
        val totalItems: Long,
        val totalPages: Int,
        val hasNext: Boolean,
        val hasPrevious: Boolean,
    )

    /**
     * Validation error response for form/request validation failures.
     *
     * @property success Always false for this response type
     * @property error Primary error message
     * @property validationErrors Map of field names to their validation error messages
     */
    @Serializable
    data class ValidationErrorResponse(
        val success: Boolean = false,
        val error: String = "Validation failed",
        val validationErrors: Map<String, List<String>>,
    )

    /**
     * Creates a success response with a message and optional data.
     *
     * @param message Success message
     * @param data Optional data payload
     * @return SuccessResponse instance
     */
    fun <T> success(
        message: String,
        data: T? = null,
    ): SuccessResponse<T> =
        SuccessResponse(
            success = true,
            message = message,
            data = data,
        )

    /**
     * Creates an error response with a message and optional details.
     *
     * @param error Error message
     * @param errorCode Optional error code
     * @param details Optional list of detailed error messages
     * @return ErrorResponse instance
     */
    fun error(
        error: String,
        errorCode: String? = null,
        details: List<String> = emptyList(),
    ): ErrorResponse =
        ErrorResponse(
            success = false,
            error = error,
            errorCode = errorCode,
            details = details,
        )

    /**
     * Creates a bulk operation response.
     *
     * @param successCount Number of successful operations
     * @param failedCount Number of failed operations
     * @param totalCount Total number of operations attempted
     * @param errors List of error messages for failed operations
     * @return BulkOperationResponse instance
     */
    fun bulkOperation(
        successCount: Int,
        failedCount: Int = 0,
        totalCount: Int,
        errors: List<String> = emptyList(),
    ): BulkOperationResponse {
        val success = failedCount == 0
        val message =
            if (success) {
                "$totalCount item(s) processed successfully"
            } else {
                "$successCount of $totalCount item(s) processed successfully, $failedCount failed"
            }

        return BulkOperationResponse(
            success = success,
            message = message,
            successCount = successCount,
            failedCount = failedCount,
            totalCount = totalCount,
            errors = errors,
        )
    }

    /**
     * Creates a validation error response.
     *
     * @param validationErrors Map of field names to error messages
     * @return ValidationErrorResponse instance
     */
    fun validationError(validationErrors: Map<String, List<String>>): ValidationErrorResponse =
        ValidationErrorResponse(
            success = false,
            error = "Validation failed",
            validationErrors = validationErrors,
        )
}
