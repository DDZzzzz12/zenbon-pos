package org.example.project.features.printer.data.repository

import org.example.project.core.domain.dto.printer.PrinterDto
import org.example.project.core.domain.enums.setup.BeepMode
import org.example.project.core.domain.enums.setup.IppDocumentFormat
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.enums.setup.PrinterType
import org.example.project.features.printer.database.tables.PrinterLocationTable
import org.example.project.features.printer.domain.models.PrinterLocation
import org.example.project.features.printer.domain.models.PrinterSetup
import org.example.project.features.printer.domain.repository.PrinterLocationRepository
import org.example.project.features.product.domain.models.ProductCategory
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.SizedCollection

/**
 * Implementation of PrinterLocationRepository for printer location data access.
 *
 * Provides concrete database operations for printer location management using
 * Exposed ORM. Exceptions propagate to the service layer for Result wrapping.
 */
class PrinterLocationRepositoryImpl : PrinterLocationRepository {
    override fun createPrinterLocation(
        printerLocation: String,
        printBill: Boolean,
        language: Language,
        beepMode: BeepMode,
        printerSetup: PrinterDto.CreatePrinterSetupRequestDto,
        categoryIds: List<Long>,
    ): PrinterLocation {
        val printerType = PrinterType.valueOf(printerSetup.printerType)
        // First, create the printer setup entity
        val newPrinterSetup =
            PrinterSetup.Companion.new {
                this.printerType = printerType
                this.printerName = printerSetup.printerName
                this.printerIp = printerSetup.printerIp
                this.printerPort = printerSetup.printerPort
                this.defaultPrinter = printerSetup.defaultPrinter
                this.ippDocumentFormat =
                    printerSetup.ippDocumentFormat?.let {
                        IppDocumentFormat.valueOf(it)
                    }
            }

        // Then create the location with the newly created printer setup
        val location =
            PrinterLocation.Companion.new {
                this.printerLocation = printerLocation
                this.printBill = printBill
                this.language = language
                this.beepMode = beepMode
                this.isActive = true
                this.printerSetup = newPrinterSetup
            }

        // Assign categories if provided
        if (categoryIds.isNotEmpty()) {
            val categories = ProductCategory.Companion.forIds(categoryIds.map { it.toInt() })
            location.productCategories = categories
        }

        return location
    }

    override fun getPrinterLocationById(id: Long): PrinterLocation? = PrinterLocation.Companion.findById(id)

    override fun getAllPrinterLocations(includeInactive: Boolean): List<PrinterLocation> {
        val locations =
            if (includeInactive) {
                PrinterLocation.all()
            } else {
                PrinterLocation.find { PrinterLocationTable.isActive eq true }
            }
        return locations.toList()
    }

    override fun getActivePrinterLocations(): List<PrinterLocation> =
        PrinterLocation.Companion
            .find {
                PrinterLocationTable.isActive eq true
            }.toList()

    override fun updatePrinterLocation(
        id: Long,
        printerLocation: String,
        printBill: Boolean,
        language: Language,
        beepMode: BeepMode,
        printerSetup: PrinterDto.UpdatePrinterSetupRequestDto,
        categoryIds: List<Long>,
    ): PrinterLocation? {
        val location = PrinterLocation.Companion.findById(id) ?: return null

        // Update the associated printer setup
        val printerType = PrinterType.valueOf(printerSetup.printerType)
        location.printerSetup.printerType = printerType
        location.printerSetup.printerName = printerSetup.printerName
        location.printerSetup.printerIp = printerSetup.printerIp
        location.printerSetup.printerPort = printerSetup.printerPort
        location.printerSetup.defaultPrinter = printerSetup.defaultPrinter
        location.printerSetup.ippDocumentFormat =
            printerSetup.ippDocumentFormat?.let {
                IppDocumentFormat.valueOf(it)
            }

        // Update location fields
        location.printerLocation = printerLocation
        location.printBill = printBill
        location.language = language
        location.beepMode = beepMode

        // Update categories
        val categories =
            if (categoryIds.isNotEmpty()) {
                ProductCategory.Companion.forIds(categoryIds.map { it.toInt() })
            } else {
                SizedCollection()
            }
        location.productCategories = categories

        return location
    }

    override fun deactivatePrinterLocation(id: Long) {
        val location =
            PrinterLocation.Companion.findById(id)
                ?: throw IllegalArgumentException("PrinterLocation with ID $id not found")

        location.isActive = false
    }

    override fun activatePrinterLocation(id: Long) {
        val location =
            PrinterLocation.Companion.findById(id)
                ?: throw IllegalArgumentException("PrinterLocation with ID $id not found")

        location.isActive = true
    }

    override fun assignCategoriesToLocation(
        id: Long,
        categoryIds: List<Long>,
    ) {
        val location =
            PrinterLocation.Companion.findById(id)
                ?: throw IllegalArgumentException("PrinterLocation with ID $id not found")

        // Update categories (replaces all existing assignments)
        val categories =
            if (categoryIds.isNotEmpty()) {
                ProductCategory.Companion.forIds(categoryIds.map { it.toInt() })
            } else {
                SizedCollection()
            }
        location.productCategories = categories
    }

    override fun getPrinterLocationByName(locationName: String): PrinterLocation? =
        PrinterLocation.Companion
            .find {
                PrinterLocationTable.printerLocation eq locationName
            }.firstOrNull()

    override fun printerLocationNameExists(
        locationName: String,
        excludeId: Long?,
    ): Boolean {
        val query =
            if (excludeId != null) {
                PrinterLocation.Companion.find {
                    (PrinterLocationTable.printerLocation eq locationName) and
                        (PrinterLocationTable.id neq excludeId)
                }
            } else {
                PrinterLocation.Companion.find {
                    PrinterLocationTable.printerLocation eq locationName
                }
            }
        return !query.empty()
    }
}
