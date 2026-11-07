/**
 * Exposed DAO entity for PrinterSetup.
 *
 * Provides object-relational mapping for printer setup with proper
 * relationship management and type-safe database operations. Supports
 * printer management including network and USB printer configuration.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.printer.domain.models

import org.example.project.features.printer.database.tables.PrinterLocationTable
import org.example.project.features.printer.database.tables.PrinterSetupTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass

/**
 * Printer setup entity for printer configuration management.
 *
 * Manages printer configuration including connection details, type,
 * and default printer settings. Supports both network and USB printers
 * for comprehensive printing capabilities.
 */
class PrinterSetup(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<PrinterSetup>(PrinterSetupTable)

    /** Type of printer connection (NETWORK, USB) */
    var printerType by PrinterSetupTable.printerType

    /** Display name for the printer */
    var printerName by PrinterSetupTable.printerName

    /** IP address for network printers */
    var printerIp by PrinterSetupTable.printerIp

    /** Port number for network printers */
    var printerPort by PrinterSetupTable.printerPort

    /** Whether this is the default printer */
    var defaultPrinter by PrinterSetupTable.defaultPrinter

    /** Document format for IPP printers (ESC_POS or PDF, null for non-IPP) */
    var ippDocumentFormat by PrinterSetupTable.ippDocumentFormat

    /** Printer location using this setup */
    val printerLocation by PrinterLocation optionalBackReferencedOn PrinterLocationTable.printerSetupId
}
