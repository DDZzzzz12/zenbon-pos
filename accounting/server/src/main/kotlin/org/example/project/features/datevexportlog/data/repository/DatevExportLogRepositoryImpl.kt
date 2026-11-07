package org.example.project.features.datevexportlog.data.repository

import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.enums.setting.DatevExportSource
import org.example.project.features.datevexportlog.database.tables.DatevExportLogTable
import org.example.project.features.datevexportlog.domain.models.DatevExportLog
import org.example.project.features.datevexportlog.domain.models.DatevExportType
import org.example.project.features.datevexportlog.domain.repository.DatevExportLogRepository
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

@OptIn(ExperimentalUuidApi::class)
class DatevExportLogRepositoryImpl : DatevExportLogRepository {
    override suspend fun insert(
        exportType: DatevExportType,
        periodStart: LocalDateTime,
        periodEnd: LocalDateTime,
        exportedByUserId: UUID,
        source: DatevExportSource,
        fileHash: String,
        fileName: String,
        rowCount: Int,
        entryCount: Int,
        periodFolder: String,
    ): DatevExportLog {
        val exportedAt = currentTimeStamp()
        val id =
            DatevExportLogTable
                .insert {
                    it[DatevExportLogTable.exportType] = exportType.name
                    it[DatevExportLogTable.periodStart] = periodStart
                    it[DatevExportLogTable.periodEnd] = periodEnd
                    it[DatevExportLogTable.exportedAt] = exportedAt
                    it[DatevExportLogTable.exportedByUserId] = exportedByUserId.toKotlinUuid()
                    it[DatevExportLogTable.exportSource] = source.name
                    it[DatevExportLogTable.fileHash] = fileHash
                    it[DatevExportLogTable.fileName] = fileName
                    it[DatevExportLogTable.rowCount] = rowCount
                    it[DatevExportLogTable.entryCount] = entryCount
                    it[DatevExportLogTable.periodFolder] = periodFolder
                }[DatevExportLogTable.id]
                .value

        return DatevExportLog(
            id = id,
            exportType = exportType,
            periodStart = periodStart,
            periodEnd = periodEnd,
            exportedAt = exportedAt,
            exportedByUserId = exportedByUserId,
            source = source,
            fileHash = fileHash,
            fileName = fileName,
            rowCount = rowCount,
            entryCount = entryCount,
            periodFolder = periodFolder,
        )
    }

    override suspend fun findById(id: Long): DatevExportLog? =
        DatevExportLogTable
            .selectAll()
            .where { DatevExportLogTable.id eq id }
            .singleOrNull()
            ?.toDomainModel()

    override suspend fun findOverlapping(
        periodStart: LocalDateTime,
        periodEnd: LocalDateTime,
        exportType: DatevExportType?,
        sourceFilter: DatevExportSource?,
    ): List<DatevExportLog> =
        DatevExportLogTable
            .selectAll()
            .where {
                val overlapCondition =
                    (DatevExportLogTable.periodStart less periodEnd) and
                        (DatevExportLogTable.periodEnd greater periodStart)
                val typeCondition =
                    if (exportType != null) {
                        overlapCondition and (DatevExportLogTable.exportType eq exportType.name)
                    } else {
                        overlapCondition
                    }
                if (sourceFilter != null) {
                    typeCondition and (DatevExportLogTable.exportSource eq sourceFilter.name)
                } else {
                    typeCondition
                }
            }.orderBy(DatevExportLogTable.exportedAt to SortOrder.DESC)
            .map { it.toDomainModel() }

    override suspend fun findLatestEnd(
        type: DatevExportType,
        sourceFilter: DatevExportSource?,
    ): LocalDateTime? =
        DatevExportLogTable
            .selectAll()
            .where {
                val typeCondition = DatevExportLogTable.exportType eq type.name
                if (sourceFilter != null) {
                    typeCondition and (DatevExportLogTable.exportSource eq sourceFilter.name)
                } else {
                    typeCondition
                }
            }.orderBy(DatevExportLogTable.periodEnd to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(DatevExportLogTable.periodEnd)

    private fun ResultRow.toDomainModel(): DatevExportLog =
        DatevExportLog(
            id = this[DatevExportLogTable.id].value,
            exportType = DatevExportType.valueOf(this[DatevExportLogTable.exportType]),
            periodStart = this[DatevExportLogTable.periodStart],
            periodEnd = this[DatevExportLogTable.periodEnd],
            exportedAt = this[DatevExportLogTable.exportedAt],
            exportedByUserId = this[DatevExportLogTable.exportedByUserId].toJavaUuid(),
            source = DatevExportSource.valueOf(this[DatevExportLogTable.exportSource]),
            fileHash = this[DatevExportLogTable.fileHash],
            fileName = this[DatevExportLogTable.fileName],
            rowCount = this[DatevExportLogTable.rowCount],
            entryCount = this[DatevExportLogTable.entryCount],
            periodFolder = this[DatevExportLogTable.periodFolder],
        )
}
