package org.example.project.core.domain.dto.setup

import kotlinx.serialization.Serializable

/**
 * Data Transfer Objects for CSV import operations.
 *
 * Provides standardized response structures for CSV import endpoints
 * ensuring consistent API responses and proper JSON serialization.
 * These DTOs are shared between client and server for type safety.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */

/**
 * Container object for all CSV import-related DTOs.
 *
 * Organizes DTOs by functionality to maintain clear separation of concerns
 * and consistent API structure across all CSV import endpoints.
 */
object CsvImportDto {
    /**
     * Standard success response for CSV import operations.
     *
     * @property success Always true for this response type
     * @property message Human-readable success message
     * @property recordCount Number of records successfully processed
     * @property details Additional success details (optional)
     */
    @Serializable
    data class ImportSuccessResponse(
        val success: Boolean = true,
        val message: String,
        val recordCount: Int,
        val details: List<String> = emptyList(),
    )

    /**
     * Standard error response for CSV import operations.
     *
     * @property success Always false for this response type
     * @property error Primary error message
     * @property details Additional error details or validation failures
     */
    @Serializable
    data class ImportErrorResponse(
        val success: Boolean = false,
        val error: String,
        val details: List<String> = emptyList(),
    )

    /**
     * CSV format information response.
     *
     * @property csvFormat Information about required CSV structure
     * @property validation File validation requirements
     * @property endpoints Available API endpoints
     * @property examples Usage examples and notes
     */
    @Serializable
    data class CsvFormatInfoResponse(
        val csvFormat: CsvFormatInfo,
        val validation: ValidationInfo,
        val endpoints: Map<String, String>,
        val examples: Map<String, String>,
    )

    /**
     * CSV format requirements.
     *
     * @property requiredHeaders List of required column headers
     * @property fieldConstraints Field validation rules and descriptions
     * @property validEnumValues Valid enum values for specific fields
     */
    @Serializable
    data class CsvFormatInfo(
        val requiredHeaders: List<String>,
        val fieldConstraints: Map<String, String>,
        val validEnumValues: Map<String, List<String>> = emptyMap(),
    )

    /**
     * File validation requirements.
     *
     * @property maxFileSize Maximum allowed file size (e.g., "5MB")
     * @property supportedFormats Supported file formats (e.g., ["CSV"])
     * @property requiredEncoding Required character encoding (e.g., "UTF-8")
     * @property note Additional validation notes
     * @property foreignKeyCheck Foreign key validation requirements (optional)
     */
    @Serializable
    data class ValidationInfo(
        val maxFileSize: String,
        val supportedFormats: List<String>,
        val requiredEncoding: String,
        val note: String,
        val foreignKeyCheck: String? = null,
    )

    /**
     * Import status response for bulk operations and system health.
     *
     * @property productCount Number of products in database
     * @property categoryCount Number of product categories in database
     * @property tableCount Number of restaurant tables in database
     * @property isSetupComplete Whether all required data is present for operation
     */
    @Serializable
    data class ImportStatusResponse(
        val productCount: Long,
        val categoryCount: Long,
        val tableCount: Long,
        val isSetupComplete: Boolean,
    )

    /**
     * Generic API error response for file upload issues.
     *
     * @property error Error message describing what went wrong
     */
    @Serializable
    data class ErrorResponse(
        val error: String,
    )
}
