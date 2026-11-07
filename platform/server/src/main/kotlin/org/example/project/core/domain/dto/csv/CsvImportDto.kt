/**
 * Data Transfer Objects for CSV import operations.
 *
 * Provides standardized response structures for CSV import endpoints
 * ensuring consistent API responses and proper JSON serialization.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.dto.csv

import kotlinx.serialization.Serializable

/**
 * Container object for all CSV import-related DTOs.
 *
 * Organizes DTOs by functionality to maintain clear separation of concerns
 * and consistent API structure across all CSV import endpoints.
 */
object CsvImportDto {
    /**
     * Standard response for CSV import operations.
     *
     * @property success Whether the import operation was successful
     * @property message Human-readable message describing the result
     * @property recordCount Number of records processed (0 if failed)
     * @property errors List of validation or processing errors (if any)
     */
    @Serializable
    data class ImportResponse(
        val success: Boolean,
        val message: String,
        val recordCount: Int = 0,
        val errors: List<String> = emptyList(),
    ) {
        companion object {
            fun success(
                message: String,
                recordCount: Int = 0,
            ) = ImportResponse(
                success = true,
                message = message,
                recordCount = recordCount,
            )

            fun failure(
                message: String,
                errors: List<String> = emptyList(),
            ) = ImportResponse(
                success = false,
                message = message,
                recordCount = 0,
                errors = errors,
            )
        }
    }

    /**
     * Error response for CSV import operations.
     *
     * @property error Human-readable error message
     * @property details Additional error details (if any)
     */
    @Serializable
    data class ErrorResponse(
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
    data class FormatInfoResponse(
        val csvFormat: CsvFormat,
        val validation: ValidationInfo,
        val endpoints: Map<String, String>,
        val examples: Map<String, String>,
    )

    /**
     * CSV format requirements.
     *
     * @property requiredHeaders List of required column headers
     * @property fieldConstraints Field validation rules
     * @property validEnumValues Valid enum values (if applicable)
     */
    @Serializable
    data class CsvFormat(
        val requiredHeaders: List<String>,
        val fieldConstraints: Map<String, String>,
        val validEnumValues: Map<String, List<String>> = emptyMap(),
    )

    /**
     * File validation requirements.
     *
     * @property maxFileSize Maximum allowed file size
     * @property supportedFormats Supported file formats
     * @property requiredEncoding Required character encoding
     * @property note Additional validation notes
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
     * Import status response for bulk operations.
     *
     * @property productCount Number of products in database
     * @property categoryCount Number of categories in database
     * @property tableCount Number of tables in database
     * @property isSetupComplete Whether all required data is present
     */
    @Serializable
    data class ImportStatusResponse(
        val productCount: Long,
        val categoryCount: Long,
        val tableCount: Long,
        val isSetupComplete: Boolean,
    )
}
