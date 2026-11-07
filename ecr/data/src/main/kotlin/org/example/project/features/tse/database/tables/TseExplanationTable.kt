package org.example.project.features.tse.database.tables

import org.example.project.core.domain.enums.tse.TseExplanationCategory
import org.example.project.core.domain.enums.tse.TseExplanationSource
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Append-only explanations for TANRs without an ordinary POS record (design 2026-09-22 D7, §4.7).
 * Rows are never updated or deleted: a correction is a new row naming the old one in [supersedesId].
 */
object TseExplanationTable : LongIdTable("tse_explanation", "id") {
    val tseSerialHex = varchar("tse_serial_hex", 100)
    val tanrFrom = long("tanr_from")
    val tanrTo = long("tanr_to")
    val category = enumerationByName<TseExplanationCategory>("category", 40)
    val text = varchar("text", 2000)

    /** The DB column is `source`; the property is renamed because `source` collides with Exposed's `ColumnSet.source`. */
    val explanationSource = enumerationByName<TseExplanationSource>("source", 10)
    val createdAt = datetime("created_at")
    val createdByEmployeeId = varchar("created_by_employee_id", 100).nullable()
    val supersedesId = long("supersedes_id").nullable()

    init {
        index("idx_tse_explanation_serial_range", false, tseSerialHex, tanrFrom, tanrTo)

        // V82's partial unique index: at most one correction per superseded entry, the structural
        // backstop behind TseFindingService.requireCorrectable. PostgreSQL only: Exposed SKIPS a
        // filtered index on H2 rather than dropping the WHERE, so the H2 suite has none. Declared
        // here so fresh and upgraded installs share one definition (FreshInstallIndexParityTest);
        // `TseOneCorrectionPerEntryIndexTest` (@Tag("postgres")) covers the constraint itself.
        uniqueIndex(
            "tse_explanation_one_correction_per_entry",
            supersedesId,
            filterCondition = { supersedesId.isNotNull() },
        )
    }
}
