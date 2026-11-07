/**
 * Service interface for printer location business logic.
 *
 * Defines contracts for printer location operations including CRUD, deactivation,
 * and category assignment. Services handle business logic, validation, and
 * transaction management.
 *
 * Implementation notes:
 * - Returns DTOs (PrinterLocationResponseDto), not entities
 * - All database operations wrapped in transactions (dbQuery)
 * - Throws [org.example.project.infrastructure.errors.PosException] on errors;
 *   the global StatusPages handler converts to application/problem+json.
 *   Migrated from Result<T, Error> in Stage B Batch 5.
 */
package org.example.project.features.printer.domain.service

import org.example.project.core.domain.dto.printer.PrinterDto

/**
 * Service for printer location business operations.
 */
interface PrinterLocationService {
    /**
     * Creates a new printer location.
     *
     * Validates the request, checks for duplicate names, and creates the location
     * with assigned categories.
     */
    suspend fun createPrinterLocation(
        employeeId: String,
        request: PrinterDto.CreatePrinterLocationRequestDto,
    ): PrinterDto.PrinterLocationResponseDto

    /**
     * Gets a printer location by ID.
     */
    suspend fun getPrinterLocationById(id: Long): PrinterDto.PrinterLocationResponseDto

    /**
     * Gets all printer locations.
     */
    suspend fun getAllPrinterLocations(includeInactive: Boolean = false): List<PrinterDto.PrinterLocationResponseDto>

    /**
     * Gets all active printer locations.
     */
    suspend fun getActivePrinterLocations(): List<PrinterDto.PrinterLocationResponseDto>

    /**
     * Updates an existing printer location.
     */
    suspend fun updatePrinterLocation(
        employeeId: String,
        id: Long,
        request: PrinterDto.UpdatePrinterLocationRequestDto,
    ): PrinterDto.PrinterLocationResponseDto

    /**
     * Deactivates a printer location (soft delete).
     */
    suspend fun deactivatePrinterLocation(
        employeeId: String,
        id: Long,
    )

    /**
     * Activates a previously deactivated printer location.
     */
    suspend fun activatePrinterLocation(id: Long)

    /**
     * Assigns product categories to a printer location.
     *
     * Replaces all existing category assignments with the new list.
     * Empty list removes all category assignments.
     */
    suspend fun assignCategoriesToLocation(
        id: Long,
        request: PrinterDto.AssignCategoriesToLocationRequestDto,
    )
}
