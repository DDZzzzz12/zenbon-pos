/**
 * Exposed table definition for printer setup configuration.
 *
 * Defines the database schema structure for printer configuration with proper
 * column types and constraints. Supports printer management including network
 * and USB printer setup for receipt and order printing.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.printer.database.tables

import org.example.project.core.domain.enums.setup.IppDocumentFormat
import org.example.project.core.domain.enums.setup.PrinterType
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Exposed table definition for printer_setup table.
 *
 * Manages printer configuration including connection details, type,
 * and default printer settings. Supports both network and USB printers
 * for comprehensive printing capabilities.
 */
object PrinterSetupTable : LongIdTable("printer_setup", "id") {
    /** Type of printer connection (NETWORK, USB) */
    val printerType = enumerationByName("printer_type", 50, PrinterType::class)

    /** Display name for the printer */
    val printerName = varchar("printer_name", 255).nullable()

    /** IP address for network printers, or IPP URI for IPP printers */
    val printerIp = varchar("printer_ip", 500).nullable()

    /** Port number for network printers */
    val printerPort = integer("printer_port").nullable()

    /** Whether this is the default printer */
    val defaultPrinter = bool("default_printer").nullable()

    /** Document format for IPP printers (ESC_POS or PDF, null for non-IPP) */
    val ippDocumentFormat = enumerationByName<IppDocumentFormat>("ipp_document_format", 20).nullable()
}
