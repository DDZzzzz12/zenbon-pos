package org.example.project.features.tse.database.tables

import org.example.project.core.domain.enums.tse.TseExportArchiveOrigin
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * The register of TSE archives (real-TSE QA rt14): every TAR an export-and-delete wrote, plus any an
 * administrator registered afterwards. After the delete such a file is the only copy of the erased
 * entries, and the DSFinV-K export reads this register to ship it for a period that reaches back into
 * them — re-checking [sha256] each time, so an altered file is never shipped.
 *
 * The ranges are what the file holds, read from its log messages when it was registered; null when it
 * holds no log message of that kind. [logTimeFrom]/[logTimeTo] are Unix seconds on the TSE's clock.
 */
object TseExportArchiveTable : LongIdTable("tse_export_archive", "id") {
    val fileName = varchar("file_name", 255)
    val filePath = varchar("file_path", 1024)
    val tseSerialHex = varchar("tse_serial_hex", 100)
    val tanrFrom = long("tanr_from").nullable()
    val tanrTo = long("tanr_to").nullable()
    val signatureCounterFrom = long("signature_counter_from").nullable()
    val signatureCounterTo = long("signature_counter_to").nullable()
    val logTimeFrom = long("log_time_from").nullable()
    val logTimeTo = long("log_time_to").nullable()
    val sizeBytes = long("size_bytes")
    val sha256 = varchar("sha256", 64)
    val origin = enumerationByName<TseExportArchiveOrigin>("origin", 20)
    val createdAt = datetime("created_at")
    val createdByEmployeeId = varchar("created_by_employee_id", 100).nullable()

    init {
        // One registration per file content: the same archive registered twice would be shipped twice.
        uniqueIndex(sha256)
        index("idx_tse_export_archive_serial_tanr", false, tseSerialHex, tanrFrom, tanrTo)
    }
}
