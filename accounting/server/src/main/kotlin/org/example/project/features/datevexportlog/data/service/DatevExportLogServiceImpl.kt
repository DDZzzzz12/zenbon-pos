package org.example.project.features.datevexportlog.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto
import org.example.project.core.domain.enums.setting.DatevExportSource
import org.example.project.core.error.PosErrorCode
import org.example.project.features.datevexportlog.domain.models.DatevExportLog
import org.example.project.features.datevexportlog.domain.models.DatevExportType
import org.example.project.features.datevexportlog.domain.repository.DatevExportLogRepository
import org.example.project.features.datevexportlog.domain.service.DatevExportLogService
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import java.security.MessageDigest
import java.util.UUID

class DatevExportLogServiceImpl(
    private val repository: DatevExportLogRepository,
) : DatevExportLogService {
    override suspend fun record(
        exportType: DatevExportType,
        periodStart: LocalDateTime,
        periodEnd: LocalDateTime,
        exportedByUserId: UUID,
        source: DatevExportSource,
        fileBytes: ByteArray,
        fileName: String,
        rowCount: Int,
        entryCount: Int,
        periodFolder: String,
    ): DatevExportLogDto {
        val fileHash = sha256Hex(fileBytes)
        return dbQuery {
            repository
                .insert(
                    exportType = exportType,
                    periodStart = periodStart,
                    periodEnd = periodEnd,
                    exportedByUserId = exportedByUserId,
                    source = source,
                    fileHash = fileHash,
                    fileName = fileName,
                    rowCount = rowCount,
                    entryCount = entryCount,
                    periodFolder = periodFolder,
                ).toDto()
        }
    }

    override suspend fun findById(id: Long): DatevExportLogDto =
        dbQuery {
            val entity = repository.findById(id) ?: throw logEntryNotFound(id)
            entity.toDto()
        }

    override suspend fun findOverlapping(
        periodStart: LocalDateTime,
        periodEnd: LocalDateTime,
        exportType: DatevExportType?,
        sourceFilter: DatevExportSource?,
    ): List<DatevExportLogDto> =
        dbQuery {
            repository
                .findOverlapping(
                    periodStart = periodStart,
                    periodEnd = periodEnd,
                    exportType = exportType,
                    sourceFilter = sourceFilter,
                ).map { it.toDto() }
        }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

private fun logEntryNotFound(id: Long) =
    PosException(
        PosErrorCode.RESOURCE_NOT_FOUND,
        HttpStatusCode.NotFound,
        "DATEV export log $id not found",
    )

private fun DatevExportLog.toDto() =
    DatevExportLogDto(
        id = id,
        exportType = exportType.name,
        periodStart = periodStart.toString(),
        periodEnd = periodEnd.toString(),
        exportedAt = exportedAt.toString(),
        exportedByUserId = exportedByUserId.toString(),
        fileHash = fileHash,
        fileName = fileName,
        rowCount = rowCount,
        entryCount = entryCount,
        periodFolder = periodFolder,
        source = source.name,
    )
