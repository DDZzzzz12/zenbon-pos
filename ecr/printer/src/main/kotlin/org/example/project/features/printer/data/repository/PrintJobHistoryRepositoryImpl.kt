/**
 * Implementation of print job history repository.
 *
 * Handles database operations for PrintJobHistory entities using Exposed ORM.
 * Creates audit trail records for all print operations. Exceptions propagate
 * to the service layer for Result wrapping.
 */
package org.example.project.features.printer.data.repository

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.dto.printer.PrinterDto
import org.example.project.core.domain.enums.setup.PrinterType
import org.example.project.features.printer.database.tables.PrintJobHistoryTable
import org.example.project.features.printer.domain.models.PrintJobHistory
import org.example.project.features.printer.domain.repository.PrintJobHistoryRepository
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Print job history repository implementation.
 *
 * Provides database access for print job audit trail.
 */
@OptIn(ExperimentalTime::class)
class PrintJobHistoryRepositoryImpl : PrintJobHistoryRepository {
    override fun createPrintJob(
        printType: PrinterDto.PrintType,
        printerType: PrinterType,
        documentId: String,
        status: PrinterDto.PrintJobStatus,
        errorMessage: String?,
        printedBy: String,
        printerLocationId: Long?,
    ): Long {
        val printJobHistory =
            PrintJobHistory.new {
                this.printType = printType
                this.printerType = printerType
                this.documentId = documentId
                this.status = status
                this.errorMessage = errorMessage
                this.printedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                this.printedBy = printedBy
                if (printerLocationId != null) {
                    this.printerLocation = org.example.project.features.printer.domain.models.PrinterLocation[printerLocationId]
                }
            }
        return printJobHistory.id.value
    }

    override fun getPrintJobsByDocumentId(documentId: String): List<PrintJobHistory> =
        PrintJobHistory
            .find { PrintJobHistoryTable.documentId eq documentId }
            .orderBy(PrintJobHistoryTable.printedAt to SortOrder.DESC)
            .toList()

    override fun getPrintJobsByDateRange(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        limit: Int,
    ): List<PrintJobHistory> =
        PrintJobHistory
            .find {
                (PrintJobHistoryTable.printedAt greaterEq startDate) and
                    (PrintJobHistoryTable.printedAt lessEq endDate)
            }.orderBy(PrintJobHistoryTable.printedAt to SortOrder.DESC)
            .limit(limit)
            .toList()

    override fun getPrintJobsByLocation(
        printerLocationId: Long,
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        limit: Int,
    ): List<PrintJobHistory> =
        PrintJobHistory
            .find {
                (PrintJobHistoryTable.printerLocationId eq printerLocationId) and
                    (PrintJobHistoryTable.printedAt greaterEq startDate) and
                    (PrintJobHistoryTable.printedAt lessEq endDate)
            }.orderBy(PrintJobHistoryTable.printedAt to SortOrder.DESC)
            .limit(limit)
            .toList()

    override fun getRecentPrintJobs(
        limit: Int,
        status: PrinterDto.PrintJobStatus?,
        printType: PrinterDto.PrintType?,
    ): List<PrintJobHistory> {
        // Build dynamic query based on filters
        val printJobs =
            when {
                status != null && printType != null -> {
                    PrintJobHistory.find {
                        (PrintJobHistoryTable.status eq status) and
                            (PrintJobHistoryTable.printType eq printType)
                    }
                }
                status != null -> {
                    PrintJobHistory.find { PrintJobHistoryTable.status eq status }
                }
                printType != null -> {
                    PrintJobHistory.find { PrintJobHistoryTable.printType eq printType }
                }
                else -> {
                    PrintJobHistory.all()
                }
            }.orderBy(PrintJobHistoryTable.printedAt to SortOrder.DESC)
                .limit(limit)
                .toList()

        return printJobs
    }
}
