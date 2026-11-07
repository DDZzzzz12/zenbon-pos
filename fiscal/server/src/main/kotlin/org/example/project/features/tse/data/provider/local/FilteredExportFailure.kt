package org.example.project.features.tse.data.provider.local

import io.ktor.http.HttpStatusCode
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import org.slf4j.LoggerFactory
import java.io.File

/**
 * What a failed time- or transaction-filtered TSE export leaves behind, and how it is reported.
 *
 * The pos-tse operations open their `tse_export_*.tar` before the device answers, so a refused
 * export left a 0-byte file in the export folder that `/api/tse/verify` then passed (real-TSE QA
 * rt12, findings b and c). And the device's "nothing in this range" answer
 * (`WORM_ERROR_EXPORT_NO_DATA_AVAILABLE`, a card-range code) surfaced as 422
 * `TSE_OPERATION_REJECTED`, which reads as a device fault; it is an empty result, a 404.
 */
object FilteredExportFailure {
    private val logger = LoggerFactory.getLogger(FilteredExportFailure::class.java)

    /** The SDK's name for "the TSE holds no log data in the requested range". */
    const val NO_DATA_ERROR = "WORM_ERROR_EXPORT_NO_DATA_AVAILABLE"

    private const val EXPORT_PREFIX = "tse_export_"

    /** The export TARs in [outputPath] now — taken before the export, to tell its leftover apart. */
    fun snapshot(outputPath: String): Set<String> = exportTars(File(outputPath)).map { it.name }.toSet()

    /**
     * Deletes the 0-byte export TARs that appeared in [outputPath] since [before]. Only empty files:
     * an export running alongside might have produced a non-empty one meanwhile, and that is its
     * deliverable, not this failure's debris.
     *
     * @return the files deleted
     */
    fun removeEmptyLeftovers(
        outputPath: String,
        before: Set<String>,
    ): List<File> =
        exportTars(File(outputPath))
            .filter { it.name !in before && it.length() == 0L }
            .filter { file ->
                file.delete().also { deleted ->
                    if (deleted) {
                        logger.info("Removed the empty TAR a failed filtered TSE export left behind: {}", file.name)
                    } else {
                        logger.warn("Could not remove the empty TAR a failed filtered TSE export left behind: {}", file.absolutePath)
                    }
                }
            }

    /** [e] as a 404 when it is the device's "no data in this range" answer; otherwise [e] unchanged. */
    fun noDataAsNotFound(
        e: PosException,
        range: String,
    ): PosException {
        if (e.code != PosErrorCode.TSE_OPERATION_REJECTED || e.message?.contains(NO_DATA_ERROR) != true) return e
        return PosException(
            PosErrorCode.TSE_TRANSACTION_NOT_FOUND,
            HttpStatusCode.NotFound,
            "The TSE holds no exportable log data for $range ($NO_DATA_ERROR); no file was written",
            cause = e,
        )
    }

    private fun exportTars(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.name.startsWith(EXPORT_PREFIX) && f.name.endsWith(".tar") }?.toList().orEmpty()
}
