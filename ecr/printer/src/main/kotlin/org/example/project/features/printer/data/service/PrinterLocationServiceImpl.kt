/**
 * Implementation of PrinterLocationService for printer location business logic.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors;
 * the global StatusPages handler converts to application/problem+json.
 * Migrated from Result<T, Error> in Stage B Batch 5.
 */
package org.example.project.features.printer.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.printer.PrinterDto
import org.example.project.core.domain.enums.setup.LogOperation
import org.example.project.core.domain.enums.websocket.ChangeEntityType
import org.example.project.core.error.PosErrorCode
import org.example.project.features.printer.domain.models.PrinterLocation
import org.example.project.features.printer.domain.repository.PrinterLocationRepository
import org.example.project.features.printer.domain.service.PrinterLocationService
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.log.EntryLogConfig
import org.example.project.utils.log.EntryLogData
import org.slf4j.LoggerFactory

/**
 * Implementation of PrinterLocationService for printer location business logic.
 *
 * Handles all business operations for printer location management including
 * validation, transaction management, and DTO/Entity conversions.
 */
class PrinterLocationServiceImpl(
    private val printerLocationRepository: PrinterLocationRepository,
) : PrinterLocationService {
    private val logger = LoggerFactory.getLogger(PrinterLocationServiceImpl::class.java)

    override suspend fun createPrinterLocation(
        employeeId: String,
        request: PrinterDto.CreatePrinterLocationRequestDto,
    ): PrinterDto.PrinterLocationResponseDto {
        logger.info(
            "createPrinterLocation: creating location='{}' by employeeId={}",
            request.printerLocation,
            employeeId,
        )
        return ExceptionConverter.catchingThrows(
            context = "createPrinterLocation(employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.PRINTER_LOCATION,
                    logOperation = LogOperation.CREATE,
                    extractLogData = { dto ->
                        EntryLogData(dto.id.toString(), dto.printerLocation, "Created printer location '${dto.printerLocation}'")
                    },
                ),
        ) {
            dbQuery {
                try {
                    request.validate()
                } catch (e: IllegalArgumentException) {
                    throw validationFailed(e.message ?: "Invalid request")
                }

                if (printerLocationRepository.printerLocationNameExists(request.printerLocation)) {
                    throw printerLocationNameTaken(request.printerLocation)
                }

                val location =
                    printerLocationRepository.createPrinterLocation(
                        printerLocation = request.printerLocation,
                        printBill = request.printBill,
                        language = request.language,
                        beepMode = request.beepMode,
                        printerSetup = request.printerSetup,
                        categoryIds = request.categoryIds,
                    )

                location.toResponseDto()
            }
        }
    }

    override suspend fun getPrinterLocationById(id: Long): PrinterDto.PrinterLocationResponseDto =
        dbQuery {
            logger.debug("getPrinterLocationById: fetching location id={}", id)
            val location =
                printerLocationRepository.getPrinterLocationById(id)
                    ?: throw printerLocationNotFound(id)
            location.toResponseDto()
        }

    override suspend fun getAllPrinterLocations(includeInactive: Boolean): List<PrinterDto.PrinterLocationResponseDto> =
        dbQuery {
            logger.debug("getAllPrinterLocations: includeInactive={}", includeInactive)
            printerLocationRepository.getAllPrinterLocations(includeInactive).map { it.toResponseDto() }
        }

    override suspend fun getActivePrinterLocations(): List<PrinterDto.PrinterLocationResponseDto> =
        dbQuery {
            printerLocationRepository.getActivePrinterLocations().map { it.toResponseDto() }
        }

    override suspend fun updatePrinterLocation(
        employeeId: String,
        id: Long,
        request: PrinterDto.UpdatePrinterLocationRequestDto,
    ): PrinterDto.PrinterLocationResponseDto {
        logger.info("updatePrinterLocation: updating location id={} by employeeId={}", id, employeeId)
        return ExceptionConverter.catchingThrows(
            context = "updatePrinterLocation(id=$id, employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig(
                    logType = ChangeEntityType.PRINTER_LOCATION,
                    logOperation = LogOperation.UPDATE,
                    extractLogData = { dto ->
                        EntryLogData(dto.id.toString(), dto.printerLocation, "Updated printer location '${dto.printerLocation}'")
                    },
                ),
        ) {
            dbQuery {
                try {
                    request.validate()
                } catch (e: IllegalArgumentException) {
                    throw validationFailed(e.message ?: "Invalid request")
                }

                if (printerLocationRepository.printerLocationNameExists(
                        locationName = request.printerLocation,
                        excludeId = id,
                    )
                ) {
                    throw printerLocationNameTaken(request.printerLocation)
                }

                val location =
                    printerLocationRepository.updatePrinterLocation(
                        id = id,
                        printerLocation = request.printerLocation,
                        printBill = request.printBill,
                        language = request.language,
                        beepMode = request.beepMode,
                        printerSetup = request.printerSetup,
                        categoryIds = request.categoryIds,
                    ) ?: throw printerLocationNotFound(id)

                location.toResponseDto()
            }
        }
    }

    override suspend fun deactivatePrinterLocation(
        employeeId: String,
        id: Long,
    ) {
        logger.info("deactivatePrinterLocation: deactivating location id={} by employeeId={}", id, employeeId)
        ExceptionConverter.catchingThrows(
            context = "deactivatePrinterLocation(id=$id, employeeId=$employeeId)",
            employeeId = employeeId,
            entryLogConfig =
                EntryLogConfig<Unit>(
                    logType = ChangeEntityType.PRINTER_LOCATION,
                    logOperation = LogOperation.DELETE,
                    extractLogData = { _ ->
                        EntryLogData(id.toString(), "PrinterLocation#$id", "Deactivated printer location $id")
                    },
                ),
        ) {
            dbQuery {
                printerLocationRepository.getPrinterLocationById(id) ?: throw printerLocationNotFound(id)
                printerLocationRepository.deactivatePrinterLocation(id)
            }
        }
    }

    override suspend fun activatePrinterLocation(id: Long) {
        logger.info("activatePrinterLocation: activating location id={}", id)
        dbQuery {
            printerLocationRepository.getPrinterLocationById(id) ?: throw printerLocationNotFound(id)
            printerLocationRepository.activatePrinterLocation(id)
        }
    }

    override suspend fun assignCategoriesToLocation(
        id: Long,
        request: PrinterDto.AssignCategoriesToLocationRequestDto,
    ) {
        dbQuery {
            printerLocationRepository.assignCategoriesToLocation(
                id = id,
                categoryIds = request.categoryIds,
            )
        }
    }

    /**
     * Extension function to convert PrinterLocation entity to response DTO.
     */
    private fun PrinterLocation.toResponseDto(): PrinterDto.PrinterLocationResponseDto =
        PrinterDto.PrinterLocationResponseDto(
            id = this.id.value,
            printerLocation = this.printerLocation,
            printBill = this.printBill,
            isActive = this.isActive,
            language = this.language,
            beepMode = this.beepMode,
            printerSetup =
                PrinterDto.PrinterSetupResponseDto(
                    id = this.printerSetup.id.value,
                    printerType = this.printerSetup.printerType.name,
                    printerName = this.printerSetup.printerName,
                    printerIp = this.printerSetup.printerIp,
                    printerPort = this.printerSetup.printerPort,
                    defaultPrinter = this.printerSetup.defaultPrinter ?: false,
                    ippDocumentFormat = this.printerSetup.ippDocumentFormat?.name,
                ),
            categoryIds = this.productCategories.map { it.id.value.toLong() },
        )

    // --- PosException helpers ---

    private fun printerLocationNotFound(id: Long) =
        PosException(
            PosErrorCode.RESOURCE_NOT_FOUND,
            HttpStatusCode.NotFound,
            "Printer location with id=$id not found",
        )

    private fun printerLocationNameTaken(name: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.Conflict,
            "Printer location '$name' already exists",
        )

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )
}
