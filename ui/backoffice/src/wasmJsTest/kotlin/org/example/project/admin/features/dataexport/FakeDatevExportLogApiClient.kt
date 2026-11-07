package org.example.project.admin.features.dataexport

import org.example.project.core.data.remote.DatevExportLogApiClient
import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto

internal class FakeDatevExportLogApiClient(
    var log: List<DatevExportLogDto> = emptyList(),
    var failWith: Throwable? = null,
    var downloadBytes: ByteArray = ByteArray(0),
) : DatevExportLogApiClient {
    var lastPeriodStart: String? = null
    var lastPeriodEnd: String? = null
    val downloadedIds = mutableListOf<Long>()

    override suspend fun findOverlapping(
        periodStart: String,
        periodEnd: String,
        exportType: String?,
    ): List<DatevExportLogDto> {
        lastPeriodStart = periodStart
        lastPeriodEnd = periodEnd
        failWith?.let { throw it }
        return log
    }

    override suspend fun downloadExport(logId: Long): ByteArray {
        downloadedIds += logId
        failWith?.let { throw it }
        return downloadBytes
    }
}

internal fun exportLogEntry(
    id: Long = 1L,
    exportType: String = "KASSENBUCH",
    fileName: String = "datev_kassenbuch_2026-07.zip",
    source: String = "AUTO",
) = DatevExportLogDto(
    id = id,
    exportType = exportType,
    periodStart = "2026-07-01T00:00",
    periodEnd = "2026-08-01T00:00",
    exportedAt = "2026-08-01T03:00:00",
    exportedByUserId = "system",
    fileHash = "abcdef0123456789",
    fileName = fileName,
    rowCount = 42,
    entryCount = 42,
    source = source,
)
