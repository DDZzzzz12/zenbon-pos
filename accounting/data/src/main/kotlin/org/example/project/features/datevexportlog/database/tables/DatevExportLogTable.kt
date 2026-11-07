package org.example.project.features.datevexportlog.database.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.datetime

// uuid() in Exposed 1.2.0 uses kotlin.uuid.Uuid internally and requires this opt-in
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
object DatevExportLogTable : LongIdTable("datev_export_log") {
    val exportType = varchar("export_type", 32)
    val periodStart = datetime("period_start")
    val periodEnd = datetime("period_end")
    val exportedAt = datetime("exported_at")
    val exportedByUserId = uuid("exported_by_user_id")
    val fileHash = varchar("file_hash", 64)
    val fileName = varchar("file_name", 255)
    val rowCount = integer("row_count")
    val entryCount = integer("entry_count")
    val periodFolder = varchar("period_folder", 64)

    /** AUTO = scheduled job; MANUAL = ad-hoc user-triggered export. */
    val exportSource = varchar("source", 8)
}
