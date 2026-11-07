/**
 * Implementation of PrintJobHistoryService for print job history business logic.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors;
 * the global StatusPages handler converts to application/problem+json.
 * Migrated from Result<T, Error> in Stage B Batch 5.
 */
package org.example.project.features.printer.data.service

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.printer.PrinterDto
import org.example.project.features.printer.domain.models.PrintJobHistory
import org.example.project.features.printer.domain.repository.PrintJobHistoryRepository
import org.example.project.features.printer.domain.service.PrintJobHistoryService
import org.example.project.utils.dbQuery
import org.slf4j.LoggerFactory

/**
 * Concrete implementation of PrintJobHistoryService.
 *
 * Manages print job history retrieval with proper transaction boundaries
 * and entity-to-DTO conversion.
 */
class PrintJobHistoryServiceImpl(
    private val printJobHistoryRepository: PrintJobHistoryRepository,
) : PrintJobHistoryService {
    private val logger = LoggerFactory.getLogger(PrintJobHistoryServiceImpl::class.java)

    override suspend fun getPrintJobsByDateRange(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        limit: Int,
        status: PrinterDto.PrintJobStatus?,
        printType: PrinterDto.PrintType?,
        locationId: Long?,
    ): List<PrinterDto.PrintJobHistoryDto> {
        logger.debug(
            "getPrintJobsByDateRange: startDate={}, endDate={}, limit={}, locationId={}",
            startDate,
            endDate,
            limit,
            locationId,
        )
        return dbQuery {
            // Choose repository method based on whether location filter is applied
            val jobs =
                if (locationId != null) {
                    printJobHistoryRepository.getPrintJobsByLocation(
                        printerLocationId = locationId,
                        startDate = startDate,
                        endDate = endDate,
                        limit = limit,
                    )
                } else {
                    printJobHistoryRepository.getPrintJobsByDateRange(
                        startDate = startDate,
                        endDate = endDate,
                        limit = limit,
                    )
                }

            // Apply in-memory filters for status and printType
            jobs
                .let { list -> if (status != null) list.filter { it.status == status } else list }
                .let { list -> if (printType != null) list.filter { it.printType == printType } else list }
                .map { it.toDto() }
        }
    }

    /**
     * Extension function to convert PrintJobHistory entity to DTO.
     */
    private fun PrintJobHistory.toDto(): PrinterDto.PrintJobHistoryDto =
        PrinterDto.PrintJobHistoryDto(
            id = this.id.value,
            printType = this.printType,
            printerType = this.printerType,
            documentId = this.documentId,
            status = this.status,
            errorMessage = this.errorMessage,
            printedAt = this.printedAt,
            printedBy = this.printedBy,
            printerLocationId = this.printerLocation?.id?.value,
            printerLocationName = this.printerLocation?.printerLocation,
        )
}
