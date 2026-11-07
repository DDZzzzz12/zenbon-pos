/**
 * Repository interface for printer configuration access.
 *
 * Provides database operations for managing printer setup configurations
 * including USB and network printer details. Returns plain entities or
 * nullable types; exceptions propagate to the service layer.
 */
package org.example.project.features.printer.domain.repository

import org.example.project.core.domain.enums.setup.PrinterType
import org.example.project.features.printer.domain.models.PrinterSetup

/**
 * Repository for printer configuration operations.
 *
 * Handles database access for PrinterSetup entities including
 * retrieval by type, ID, and default printer selection.
 */
interface PrinterConfigurationRepository {
    /**
     * Get the default printer configuration.
     *
     * Returns the printer marked as default in the system.
     * If no default printer is set, returns null.
     *
     * @return PrinterSetup or null if no default is set
     */
    fun getDefaultPrinter(): PrinterSetup?

    /**
     * Get all printers of a specific type.
     *
     * Filters printers by connection type (USB or NETWORK).
     *
     * @param type Printer connection type to filter by
     * @return List of matching printers (empty if none found)
     */
    fun getPrintersByType(type: PrinterType): List<PrinterSetup>

    /**
     * Get a specific printer by ID.
     *
     * Retrieves printer configuration by its database ID.
     *
     * @param id Printer configuration ID
     * @return PrinterSetup or null if not found
     */
    fun getPrinterById(id: Long): PrinterSetup?
}
