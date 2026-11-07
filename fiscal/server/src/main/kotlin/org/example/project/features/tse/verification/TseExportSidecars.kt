package org.example.project.features.tse.verification

import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.tse.TseDto
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.time.Clock

/**
 * Writes and reads the [TseExportFilterSidecar] beside a filtered TSE export (`<file>.json`).
 *
 * Writing never fails the export: the TAR is already on disk when it runs, and a missing sidecar
 * only means `/api/tse/verify` judges the file as strictly as an unfiltered export — the safe
 * direction. Full, incremental and export-and-delete archives get no sidecar.
 */
class TseExportSidecars(
    private val clock: Clock = Clock.System,
) {
    private val logger = LoggerFactory.getLogger(TseExportSidecars::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /** The filter a filtered export was asked for; the ranges not used by that export stay null. */
    data class Filter(
        val clientId: String?,
        val startEpochMillis: Long? = null,
        val endEpochMillis: Long? = null,
        val startTransaction: Long? = null,
        val endTransaction: Long? = null,
    )

    /** Records [filter] next to the TAR [result] wrote. A failed or file-less result writes nothing. */
    fun record(
        result: TseDto.ExportResultDto,
        filter: Filter,
    ) {
        val path = result.filePath?.takeIf { result.success && it.isNotBlank() } ?: return
        val tar = File(path)
        try {
            if (!tar.isFile) return
            val sidecar =
                TseExportFilterSidecar(
                    fileName = tar.name,
                    clientId = filter.clientId?.takeIf(String::isNotBlank),
                    startEpochMillis = filter.startEpochMillis,
                    endEpochMillis = filter.endEpochMillis,
                    startTransaction = filter.startTransaction,
                    endTransaction = filter.endTransaction,
                    requestedAt = clock.now().toString(),
                    sha256 = sha256Hex(tar.readBytes()),
                )
            sidecarOf(tar).writeText(json.encodeToString(TseExportFilterSidecar.serializer(), sidecar))
        } catch (e: Exception) {
            logger.warn("TseExportSidecars: no filter sidecar written for {}; verify will judge it unfiltered", tar.name, e)
        }
    }

    /** The sidecar of [tar], or null when there is none or it cannot be read. */
    fun read(tar: File): TseExportFilterSidecar? {
        val file = sidecarOf(tar)
        if (!file.isFile) return null
        return try {
            json.decodeFromString(TseExportFilterSidecar.serializer(), file.readText())
        } catch (e: Exception) {
            logger.warn("TseExportSidecars: unreadable filter sidecar {}; verifying {} unfiltered", file.name, tar.name, e)
            null
        }
    }

    private fun sidecarOf(tar: File): File = File(tar.parentFile, "${tar.name}.json")
}
