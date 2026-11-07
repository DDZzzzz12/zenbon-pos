/**
 * Container object for all Printer-related DTOs.
 *
 * Organizes DTOs by functionality: print requests, responses, configuration,
 * and job history to maintain clear separation of concerns and consistent API structure.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.core.domain.dto.printer

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import org.example.project.core.domain.enums.setup.BeepMode
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.enums.setup.PrinterType

/**
 * Container object for all Printer-related DTOs.
 *
 * Supports thermal printer operations including USB and network printing,
 * ESC/POS protocol formatting, and multi-language character encoding.
 */
object PrinterDto {
    /**
     * Response DTO for print operations.
     *
     * Contains the result of a print operation including success status,
     * message, optional print job ID for tracking, and timestamp.
     *
     * @property success Whether the print operation succeeded
     * @property message Success or error message
     * @property printJobId Optional ID of the print job in audit trail
     * @property timestamp ISO-8601 formatted timestamp of the operation
     */
    @Serializable
    data class PrintResponseDto(
        val success: Boolean,
        val message: String,
        val printJobId: Long? = null,
        val timestamp: String,
    )

    /**
     * Configuration DTO for printer setup.
     *
     * Maps to the PrinterSetup database entity and provides printer
     * configuration details for USB or network printers.
     *
     * @property id Printer configuration ID
     * @property printerType Type of printer connection
     * @property printerName Name of USB printer (null for network printers)
     * @property printerIp IP address of network printer (null for USB printers)
     * @property printerPort Port of network printer (null for USB printers)
     * @property isDefault Whether this is the default printer
     */
    @Serializable
    data class PrinterConfigDto(
        val id: Long,
        val printerType: PrinterType,
        val printerName: String?,
        val printerIp: String?,
        val printerPort: Int?,
        val isDefault: Boolean,
    )

    /**
     * Print job history DTO for audit trail.
     *
     * Tracks all print operations for compliance and troubleshooting.
     * Every print attempt is logged regardless of success or failure.
     *
     * @property id Print job history ID
     * @property printType Type of document that was printed
     * @property printerType Type of printer used
     * @property documentId ID of the document that was printed
     * @property status Status of the print job
     * @property errorMessage Error message if print failed (null if successful)
     * @property printedAt Timestamp of print operation (for date filtering)
     * @property printedBy Employee number who initiated the print
     * @property printerLocationId ID of printer location where print was executed
     * @property printerLocationName Name of printer location (Kitchen, Bar, etc.)
     */
    @Serializable
    data class PrintJobHistoryDto(
        val id: Long,
        val printType: PrintType,
        val printerType: PrinterType,
        val documentId: String,
        val status: PrintJobStatus,
        val errorMessage: String?,
        val printedAt: LocalDateTime,
        val printedBy: String,
        val printerLocationId: Long? = null,
        val printerLocationName: String? = null,
    )

    /**
     * Enum for print document types.
     *
     * Defines the types of documents that can be printed:
     * - BILL: Customer bill/receipt
     * - ORDER: Kitchen/bar order ticket
     * - ZBON: End-of-day Z report
     * - TEST_PRINT: Printer test page
     */
    @Serializable
    enum class PrintType {
        /** Customer bill/receipt */
        BILL,

        /** Kitchen/bar order ticket */
        ORDER,

        /** End-of-day Z report */
        ZBON,

        /** Printer test page */
        TEST_PRINT,

        /** Reference receipt for a table's open items */
        REFERENCE_PRINT,
    }

    /**
     * Enum for print job status.
     *
     * Tracks the outcome of print operations in the audit trail:
     * - SUCCESS: Print completed successfully
     * - FAILED: Print failed with error
     * - PENDING: Print queued but not yet executed
     */
    @Serializable
    enum class PrintJobStatus {
        /** Print completed successfully */
        SUCCESS,

        /** Print failed with error */
        FAILED,

        /** Print queued but not yet executed */
        PENDING,
    }

    // ============================================================
    // PRINTER SETUP DTOs
    // ============================================================

    /**
     * Response DTO for printer setup configuration.
     *
     * Contains complete printer setup information including connection details.
     *
     * @property id Printer setup ID
     * @property printerType Type of printer (USB, NETWORK, or IPP)
     * @property printerName Name of the printer
     * @property printerIp IP address for network printers, or IPP URI for IPP printers (null for USB)
     * @property printerPort Port for network printers (null for USB and IPP)
     * @property defaultPrinter Whether this is the default printer
     * @property ippDocumentFormat Document format for IPP printers: ESC_POS or PDF (null for non-IPP)
     */
    @Serializable
    data class PrinterSetupResponseDto(
        val id: Long,
        val printerType: String,
        val printerName: String?,
        val printerIp: String?,
        val printerPort: Int?,
        val defaultPrinter: Boolean,
        val ippDocumentFormat: String? = null,
    )

    /**
     * Request DTO for creating a new printer setup.
     *
     * @property printerType Type of printer (USB, NETWORK, or IPP)
     * @property printerName Name of the printer
     * @property printerIp IP address for network printers, or IPP URI for IPP printers (required for NETWORK/IPP)
     * @property printerPort Port for network printers (required for NETWORK type)
     * @property defaultPrinter Whether this should be the default printer
     * @property ippDocumentFormat Document format for IPP printers: ESC_POS or PDF (required for IPP type)
     */
    @Serializable
    data class CreatePrinterSetupRequestDto(
        val printerType: String,
        val printerName: String,
        val printerIp: String? = null,
        val printerPort: Int? = null,
        val defaultPrinter: Boolean = false,
        val ippDocumentFormat: String? = null,
    ) {
        /**
         * Validates the printer setup request.
         *
         * Ensures type-specific requirements are met:
         * - NETWORK: requires IP address and port
         * - IPP: requires IPP URI (stored in printerIp) and document format
         */
        fun validate() {
            require(printerName.isNotBlank()) { "Printer name is required" }
            require(printerType in setOf("USB", "NETWORK", "IPP")) { "Printer type must be USB, NETWORK, or IPP" }

            if (printerType == "NETWORK") {
                require(!printerIp.isNullOrBlank()) { "IP address is required for network printers" }
                require(printerPort != null && printerPort in 1..65535) {
                    "Port must be between 1 and 65535 for network printers"
                }
            }

            if (printerType == "IPP") {
                require(!printerIp.isNullOrBlank()) { "IPP URI is required for IPP printers" }
                require(!ippDocumentFormat.isNullOrBlank()) { "Document format is required for IPP printers" }
                require(ippDocumentFormat in setOf("ESC_POS", "PDF")) {
                    "Document format must be ESC_POS or PDF"
                }
            }
        }
    }

    /**
     * Request DTO for updating an existing printer setup.
     *
     * @property printerType Type of printer (USB, NETWORK, or IPP)
     * @property printerName Name of the printer
     * @property printerIp IP address for network printers, or IPP URI for IPP printers (required for NETWORK/IPP)
     * @property printerPort Port for network printers (required for NETWORK type)
     * @property defaultPrinter Whether this should be the default printer
     * @property ippDocumentFormat Document format for IPP printers: ESC_POS or PDF (required for IPP type)
     */
    @Serializable
    data class UpdatePrinterSetupRequestDto(
        val printerType: String,
        val printerName: String,
        val printerIp: String? = null,
        val printerPort: Int? = null,
        val defaultPrinter: Boolean = false,
        val ippDocumentFormat: String? = null,
    ) {
        /**
         * Validates the printer setup update request.
         *
         * Ensures type-specific requirements are met:
         * - NETWORK: requires IP address and port
         * - IPP: requires IPP URI (stored in printerIp) and document format
         */
        fun validate() {
            require(printerName.isNotBlank()) { "Printer name is required" }
            require(printerType in setOf("USB", "NETWORK", "IPP")) { "Printer type must be USB, NETWORK, or IPP" }

            if (printerType == "NETWORK") {
                require(!printerIp.isNullOrBlank()) { "IP address is required for network printers" }
                require(printerPort != null && printerPort in 1..65535) {
                    "Port must be between 1 and 65535 for network printers"
                }
            }

            if (printerType == "IPP") {
                require(!printerIp.isNullOrBlank()) { "IPP URI is required for IPP printers" }
                require(!ippDocumentFormat.isNullOrBlank()) { "Document format is required for IPP printers" }
                require(ippDocumentFormat in setOf("ESC_POS", "PDF")) {
                    "Document format must be ESC_POS or PDF"
                }
            }
        }
    }

    // ============================================================
    // PRINTER LOCATION DTOs
    // ============================================================

    /**
     * Response DTO for printer location configuration.
     *
     * Contains complete printer location information including assigned printer and categories.
     *
     * @property id Printer location ID
     * @property printerLocation Location name (Kitchen, Bar, etc.)
     * @property printBill Whether this location can print customer bills
     * @property isActive Whether this location is active (soft delete flag)
     * @property language Language for print formatting (determines character encoding)
     * @property printerSetup Associated printer setup configuration
     * @property categoryIds List of product category IDs assigned to this location
     */
    @Serializable
    data class PrinterLocationResponseDto(
        val id: Long,
        val printerLocation: String,
        val printBill: Boolean,
        val isActive: Boolean,
        val language: Language,
        val beepMode: BeepMode = BeepMode.DISABLED,
        val printerSetup: PrinterSetupResponseDto?,
        val categoryIds: List<Long>,
    )

    /**
     * Request DTO for creating a new printer location.
     *
     * @property printerLocation Location name (Kitchen, Bar, etc.)
     * @property printBill Whether this location can print customer bills
     * @property language Language for print formatting (defaults to GERMAN)
     * @property printerSetup Printer setup configuration to create
     * @property categoryIds List of product category IDs to assign to this location
     */
    @Serializable
    data class CreatePrinterLocationRequestDto(
        val printerLocation: String,
        val printBill: Boolean,
        val language: Language = Language.GERMAN,
        val beepMode: BeepMode = BeepMode.DISABLED,
        val printerSetup: CreatePrinterSetupRequestDto,
        val categoryIds: List<Long> = emptyList(),
    ) {
        /**
         * Validates the printer location request.
         */
        fun validate() {
            require(printerLocation.isNotBlank()) { "Location name is required" }
        }
    }

    /**
     * Request DTO for updating an existing printer location.
     *
     * @property printerLocation Location name (Kitchen, Bar, etc.)
     * @property printBill Whether this location can print customer bills
     * @property language Language for print formatting (defaults to GERMAN)
     * @property printerSetup Updated printer setup configuration
     * @property categoryIds List of product category IDs to assign to this location
     */
    @Serializable
    data class UpdatePrinterLocationRequestDto(
        val printerLocation: String,
        val printBill: Boolean,
        val language: Language = Language.GERMAN,
        val beepMode: BeepMode = BeepMode.DISABLED,
        val printerSetup: UpdatePrinterSetupRequestDto,
        val categoryIds: List<Long> = emptyList(),
    ) {
        /**
         * Validates the printer location update request.
         */
        fun validate() {
            require(printerLocation.isNotBlank()) { "Location name is required" }
            printerSetup.validate()
        }
    }

    /**
     * Request DTO for assigning categories to a printer location.
     *
     * @property categoryIds List of product category IDs to assign
     */
    @Serializable
    data class AssignCategoriesToLocationRequestDto(
        val categoryIds: List<Long>,
    )

    // ============================================================
    // IPP PRINTER DISCOVERY DTOs
    // ============================================================

    /**
     * DTO for a printer discovered via CUPS/IPP protocol.
     *
     * Contains printer metadata obtained through IPP service discovery,
     * used by the client UI to display available printers and auto-fill
     * printer setup fields.
     *
     * @property name Human-readable printer name
     * @property uri IPP URI for addressing the printer (e.g., "ipp://192.168.1.100/ipp/print")
     * @property makeAndModel Printer manufacturer and model string
     * @property location Physical location description (if configured on the printer)
     * @property state Current printer state (idle, processing, stopped, unknown)
     * @property connectionType Connection protocol type (always "IPP" for discovered printers)
     * @property supportedFormats List of supported document formats (e.g., "application/pdf", "application/octet-stream")
     */
    @Serializable
    data class DiscoveredPrinterDto(
        val name: String,
        val uri: String,
        val makeAndModel: String? = null,
        val location: String? = null,
        val state: String,
        val connectionType: String = "IPP",
        val supportedFormats: List<String> = emptyList(),
    )
}
