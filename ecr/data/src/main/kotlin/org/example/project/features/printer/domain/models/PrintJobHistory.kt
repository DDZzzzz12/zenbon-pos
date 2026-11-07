/**
 * Exposed DAO entity for PrintJobHistory.
 *
 * Provides object-relational mapping for print job audit trail with proper
 * relationship management and type-safe database operations. Tracks all print
 * operations for compliance and troubleshooting.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.printer.domain.models

import org.example.project.features.printer.database.tables.PrintJobHistoryTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.*

/**
 * Print job history entity for audit trail.
 *
 * Records every print operation attempt (successful or failed) for
 * compliance tracking and troubleshooting purposes.
 */
class PrintJobHistory(
    id: EntityID<Long>,
) : LongEntity(id) {
    companion object : LongEntityClass<PrintJobHistory>(PrintJobHistoryTable)

    /** Type of document that was printed (BILL, ORDER, ZBON) */
    var printType by PrintJobHistoryTable.printType

    /** Type of printer used (USB, NETWORK) */
    var printerType by PrintJobHistoryTable.printerType

    /** ID of the document that was printed */
    var documentId by PrintJobHistoryTable.documentId

    /** Status of the print job (SUCCESS, FAILED, PENDING) */
    var status by PrintJobHistoryTable.status

    /** Error message if print failed (null if successful) */
    var errorMessage by PrintJobHistoryTable.errorMessage

    /** Timestamp of print operation (LocalDateTime for better filtering) */
    var printedAt by PrintJobHistoryTable.printedAt

    /** Employee number who initiated the print */
    var printedBy by PrintJobHistoryTable.printedBy

    /** Printer location where this print was executed (Kitchen, Bar, etc.) */
    var printerLocation by PrinterLocation optionalReferencedOn PrintJobHistoryTable.printerLocationId
}
