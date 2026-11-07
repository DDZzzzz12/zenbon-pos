/**
 * Repository interface for printer location data access operations.
 *
 * Defines contracts for all printer location-related database operations following
 * the Repository pattern. Returns plain entities or nullable types; exceptions
 * propagate to the service layer.
 */
package org.example.project.features.printer.domain.repository

import org.example.project.core.domain.dto.printer.PrinterDto
import org.example.project.core.domain.enums.setup.BeepMode
import org.example.project.core.domain.enums.setup.Language
import org.example.project.features.printer.domain.models.PrinterLocation

/**
 * Repository for printer location data access.
 */
interface PrinterLocationRepository {
    /**
     * Creates a new printer location.
     *
     * @param printerLocation Location name (Kitchen, Bar, etc.)
     * @param printBill Whether this location can print customer bills
     * @param language Language for print formatting
     * @param printerSetup Printer setup to assign
     * @param categoryIds List of product category IDs to assign
     * @return Created PrinterLocation entity
     */
    fun createPrinterLocation(
        printerLocation: String,
        printBill: Boolean,
        language: Language,
        beepMode: BeepMode = BeepMode.DISABLED,
        printerSetup: PrinterDto.CreatePrinterSetupRequestDto,
        categoryIds: List<Long>,
    ): PrinterLocation

    /**
     * Gets a printer location by ID.
     *
     * @param id Printer location ID
     * @return PrinterLocation entity or null if not found
     */
    fun getPrinterLocationById(id: Long): PrinterLocation?

    /**
     * Gets all printer locations.
     *
     * @param includeInactive Whether to include deactivated locations
     * @return List of PrinterLocation entities
     */
    fun getAllPrinterLocations(includeInactive: Boolean = false): List<PrinterLocation>

    /**
     * Gets all active printer locations.
     *
     * @return List of active PrinterLocation entities
     */
    fun getActivePrinterLocations(): List<PrinterLocation>

    /**
     * Updates an existing printer location.
     *
     * @param id Printer location ID
     * @param printerLocation New location name
     * @param printBill New print bill capability flag
     * @param language Language for print formatting
     * @param printerSetup Updated printer setup configuration
     * @param categoryIds New list of category IDs
     * @return Updated PrinterLocation entity or null if not found
     */
    fun updatePrinterLocation(
        id: Long,
        printerLocation: String,
        printBill: Boolean,
        language: Language,
        beepMode: BeepMode = BeepMode.DISABLED,
        printerSetup: PrinterDto.UpdatePrinterSetupRequestDto,
        categoryIds: List<Long>,
    ): PrinterLocation?

    /**
     * Deactivates a printer location (soft delete).
     *
     * @param id Printer location ID
     * @throws IllegalArgumentException if not found
     */
    fun deactivatePrinterLocation(id: Long)

    /**
     * Activates a previously deactivated printer location.
     *
     * @param id Printer location ID
     * @throws IllegalArgumentException if not found
     */
    fun activatePrinterLocation(id: Long)

    /**
     * Assigns product categories to a printer location.
     *
     * Replaces all existing category assignments with the new list.
     *
     * @param id Printer location ID
     * @param categoryIds List of product category IDs to assign
     * @throws IllegalArgumentException if not found
     */
    fun assignCategoriesToLocation(
        id: Long,
        categoryIds: List<Long>,
    )

    /**
     * Gets printer location by name.
     *
     * @param locationName Location name to search for
     * @return PrinterLocation entity or null if not found
     */
    fun getPrinterLocationByName(locationName: String): PrinterLocation?

    /**
     * Checks if a printer location name exists (excluding a specific ID).
     *
     * @param locationName Location name to check
     * @param excludeId ID to exclude from check (for update operations)
     * @return Boolean indicating if name exists
     */
    fun printerLocationNameExists(
        locationName: String,
        excludeId: Long? = null,
    ): Boolean
}
