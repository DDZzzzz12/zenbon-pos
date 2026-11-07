package org.example.project.features.tse.data.repository

import org.example.project.features.tse.database.tables.TseExportArchiveTable
import org.example.project.features.tse.domain.models.TseExportArchiveRecord
import org.example.project.features.tse.domain.repository.TseExportArchiveRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll

class TseExportArchiveRepositoryImpl : TseExportArchiveRepository {
    override fun findBySha256(sha256: String): TseExportArchiveRecord? =
        TseExportArchiveTable
            .selectAll()
            .where { TseExportArchiveTable.sha256 eq sha256.lowercase() }
            .singleOrNull()
            ?.toRecord()

    override fun findBySerial(serialHex: String): List<TseExportArchiveRecord> =
        TseExportArchiveTable
            .selectAll()
            .where { TseExportArchiveTable.tseSerialHex eq serialHex.uppercase() }
            .orderBy(TseExportArchiveTable.tanrFrom to SortOrder.ASC_NULLS_LAST, TseExportArchiveTable.id to SortOrder.ASC)
            .map { it.toRecord() }

    override fun insert(record: TseExportArchiveRecord): TseExportArchiveRecord {
        val id =
            TseExportArchiveTable.insertAndGetId {
                it[fileName] = record.fileName
                it[filePath] = record.filePath
                it[tseSerialHex] = record.tseSerialHex.uppercase()
                it[tanrFrom] = record.tanrFrom
                it[tanrTo] = record.tanrTo
                it[signatureCounterFrom] = record.signatureCounterFrom
                it[signatureCounterTo] = record.signatureCounterTo
                it[logTimeFrom] = record.logTimeFrom
                it[logTimeTo] = record.logTimeTo
                it[sizeBytes] = record.sizeBytes
                it[sha256] = record.sha256.lowercase()
                it[origin] = record.origin
                it[createdAt] = record.createdAt
                it[createdByEmployeeId] = record.createdByEmployeeId
            }
        return record.copy(id = id.value, tseSerialHex = record.tseSerialHex.uppercase(), sha256 = record.sha256.lowercase())
    }

    private fun ResultRow.toRecord(): TseExportArchiveRecord =
        TseExportArchiveRecord(
            id = this[TseExportArchiveTable.id].value,
            fileName = this[TseExportArchiveTable.fileName],
            filePath = this[TseExportArchiveTable.filePath],
            tseSerialHex = this[TseExportArchiveTable.tseSerialHex],
            tanrFrom = this[TseExportArchiveTable.tanrFrom],
            tanrTo = this[TseExportArchiveTable.tanrTo],
            signatureCounterFrom = this[TseExportArchiveTable.signatureCounterFrom],
            signatureCounterTo = this[TseExportArchiveTable.signatureCounterTo],
            logTimeFrom = this[TseExportArchiveTable.logTimeFrom],
            logTimeTo = this[TseExportArchiveTable.logTimeTo],
            sizeBytes = this[TseExportArchiveTable.sizeBytes],
            sha256 = this[TseExportArchiveTable.sha256],
            origin = this[TseExportArchiveTable.origin],
            createdAt = this[TseExportArchiveTable.createdAt],
            createdByEmployeeId = this[TseExportArchiveTable.createdByEmployeeId],
        )
}
