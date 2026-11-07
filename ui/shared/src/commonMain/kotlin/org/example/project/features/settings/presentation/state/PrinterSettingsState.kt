/**
 * Printer settings state management.
 *
 * Defines the state structure for printer configuration management, including:
 * - Printer setup management (USB/Network printers)
 * - Printer location management (Kitchen, Bar, etc.)
 * - Print job history (audit trail)
 *
 * Follows the established pattern from Business Settings with expandable sections
 * and dialog-based data entry.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.settings.presentation.state

import org.example.project.core.domain.dto.printer.PrinterDto
import org.example.project.core.domain.dto.product.ProductCategoryDto
import org.example.project.core.presentation.error.UiError
import org.example.project.features.settings.presentation.components.common.DateRangeOption

/**
 * Main printer settings state container.
 *
 * @property printerSetupSection State for printer setup management section
 * @property printerLocationSection State for printer location management section
 * @property printJobHistorySection State for print job history section
 * @property printSettingsEnabled Whether print settings are globally enabled
 * @property isLoadingPrintSettings Loading state for print settings configuration
 * @property isLoading Global loading state
 * @property error UiError to display (if any)
 * @property saveSuccess Indicates if save operation was successful
 */
data class PrinterSettingsState(
    val printerSetupSection: PrinterSetupManagementState = PrinterSetupManagementState(),
    val printerLocationSection: PrinterLocationManagementState = PrinterLocationManagementState(),
    val printJobHistorySection: PrintJobHistoryState = PrintJobHistoryState(),
    val printSettingsEnabled: Boolean = false,
    val isLoadingPrintSettings: Boolean = false,
    val isLoading: Boolean = false,
    val error: UiError? = null,
    val saveSuccess: Boolean = false,
    val testPrintingLocationId: Long? = null,
    val testPrintSuccess: Boolean = false,
)

/**
 * Printer setup management section state.
 *
 * Manages the state for the printer setup section where users can:
 * - View list of configured printers
 * - Add new printers (USB or Network)
 * - Edit existing printer configurations
 * - Delete printers
 * - Set default printer
 *
 * @property isExpanded Whether the section is expanded or collapsed
 * @property printers List of configured printers
 * @property showAddDialog Whether the add/edit dialog is visible
 * @property editingPrinter Printer being edited (null for add operation)
 * @property isLoadingPrinters Loading state for printer list
 */
data class PrinterSetupManagementState(
    val isExpanded: Boolean = false,
    val printers: List<PrinterDto.PrinterSetupResponseDto> = emptyList(),
    val showAddDialog: Boolean = false,
    val editingPrinter: PrinterDto.PrinterSetupResponseDto? = null,
    val isLoadingPrinters: Boolean = false,
)

/**
 * Printer location management section state.
 *
 * Manages the state for the printer location section where users can:
 * - View list of configured printer locations (Kitchen, Bar, etc.)
 * - Assign printers to locations
 * - Map product categories to locations for distributed printing
 * - Configure whether a location prints customer bills
 *
 * @property isExpanded Whether the section is expanded or collapsed
 * @property locations List of configured printer locations
 * @property availablePrinters List of printers available for assignment
 * @property availableCategories List of product categories for mapping
 * @property showAddDialog Whether the add/edit dialog is visible
 * @property editingLocation Location being edited (null for add operation)
 * @property isLoadingLocations Loading state for location list
 * @property discoveredPrinters List of printers discovered via CUPS/IPP
 * @property isDiscovering Whether a printer discovery scan is in progress
 * @property showDiscoveryDialog Whether the printer discovery dialog is visible
 */
data class PrinterLocationManagementState(
    val isExpanded: Boolean = false,
    val locations: List<PrinterDto.PrinterLocationResponseDto> = emptyList(),
    val availablePrinters: List<PrinterDto.PrinterSetupResponseDto> = emptyList(),
    val availableCategories: List<ProductCategoryDto.ProductCategoryResponseDto> = emptyList(),
    val showAddDialog: Boolean = false,
    val editingLocation: PrinterDto.PrinterLocationResponseDto? = null,
    val isLoadingLocations: Boolean = false,
    val discoveredPrinters: List<PrinterDto.DiscoveredPrinterDto> = emptyList(),
    val isDiscovering: Boolean = false,
    val showDiscoveryDialog: Boolean = false,
)

/**
 * Print job history section state.
 *
 * Manages the state for the print job history section where users can:
 * - View recent print operations (last 50)
 * - See print job status (SUCCESS/FAILED/PENDING)
 * - Filter by status, print type, and date range
 * - View error messages for failed jobs
 * - Track which employee performed the print operation
 *
 * This is a read-only audit trail section.
 *
 * @property isExpanded Whether the section is expanded or collapsed
 * @property history List of print job history entries
 * @property isLoadingHistory Loading state for history list
 * @property statusFilter Optional filter by print job status
 * @property printTypeFilter Optional filter by print type
 * @property selectedDateRange Currently selected date range option (default: last 7 days, so a
 *   quiet day does not read as "no history")
 * @property locationId Optional printer-location filter (null = all locations)
 */
data class PrintJobHistoryState(
    val isExpanded: Boolean = false,
    val history: List<PrinterDto.PrintJobHistoryDto> = emptyList(),
    val isLoadingHistory: Boolean = false,
    val statusFilter: PrinterDto.PrintJobStatus? = null,
    val printTypeFilter: PrinterDto.PrintType? = null,
    val selectedDateRange: DateRangeOption = DateRangeOption.LAST_7_DAYS,
    val locationId: Long? = null,
)
