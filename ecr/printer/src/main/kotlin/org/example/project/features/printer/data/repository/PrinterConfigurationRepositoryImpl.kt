/**
 * Implementation of printer configuration repository.
 *
 * Handles database operations for PrinterSetup entities using Exposed ORM.
 * Returns entities for service layer conversion to DTOs.
 */
package org.example.project.features.printer.data.repository

import org.example.project.core.domain.enums.setup.PrinterType
import org.example.project.features.printer.database.tables.PrinterSetupTable
import org.example.project.features.printer.domain.models.PrinterSetup
import org.example.project.features.printer.domain.repository.PrinterConfigurationRepository
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Printer configuration repository implementation.
 *
 * Provides database access for printer setup configurations.
 * Exceptions propagate to the service layer for Result wrapping.
 */
class PrinterConfigurationRepositoryImpl : PrinterConfigurationRepository {
    override fun getDefaultPrinter(): PrinterSetup? = PrinterSetup.find { PrinterSetupTable.defaultPrinter eq true }.firstOrNull()

    override fun getPrintersByType(type: PrinterType): List<PrinterSetup> = PrinterSetup.find { PrinterSetupTable.printerType eq type }.toList()

    override fun getPrinterById(id: Long): PrinterSetup? = PrinterSetup.findById(id)
}
