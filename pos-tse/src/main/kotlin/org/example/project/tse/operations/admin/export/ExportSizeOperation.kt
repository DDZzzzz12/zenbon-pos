/**
 * TSE Export Size Calculation Operation.
 *
 * Calculates the expected export size without performing the actual export.
 * Useful for estimating export time, checking available disk space,
 * and progress bar calculation.
 *
 * This is a read-only query that does not require an exclusive lock.
 *
 * WormAPI Methods:
 * - info().tarExportSize() for full export size
 * - export_tar_incremental_size() for incremental export size - Line 1443
 *
 * @param adminPin Admin PIN for TSE authentication
 * @param lastState Previous incremental export state (null for first query)
 * @since 2.0.0
 */
package org.example.project.tse.operations.admin.export

import com.secureflashcard.wormapi.WormException
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.tse.models.TseStatus
import org.example.project.tse.operations.admin.BaseTseOperation
import org.example.project.tse.operations.signing.WormErrorClass

class ExportSizeOperation(
    adminPin: String,
    private val lastState: ByteArray? = null
) : BaseTseOperation<TseDto.ExportSizeDto>(adminPin, requiresLock = false) {

    /**
     * Calculate export size without performing export.
     *
     * @param tseStatus Current TSE status and configuration
     * @return Result with size information for both full and incremental exports
     */
    override fun performOperation(tseStatus: TseStatus): TseDto.ExportSizeDto {
        logger.info("Calculating TSE export size...")

        val store = getWormStore(tseStatus)

        // Get full export size from info. Both values are read inside ONE use{} block:
        // close() frees the native handle, and reading it afterwards is a use-after-free
        // that SIGSEGVs libWormAPIJni on a real TSE.
        val (fullExportSize, fullExportSizeSectors) = store.info().use { info ->
            info.tarExportSize() to info.tarExportSizeInSectors()
        }

        // Get incremental export size. With a current lastState the SDK answers
        // WORM_ERROR_INCREMENTAL_EXPORT_NO_DATA: nothing new to export, i.e. size 0, not a failure.
        val incrementalSize = incrementalOrZero { store.export_tar_incremental_size(lastState) }
        val incrementalSizeSectors = incrementalOrZero { store.export_tar_incremental_sizeInSectors(lastState) }

        logger.info("Full export size: $fullExportSize bytes, Incremental: $incrementalSize bytes")

        return TseDto.ExportSizeDto(
            fullExportSizeBytes = fullExportSize,
            fullExportSizeSectors = fullExportSizeSectors,
            incrementalExportSizeBytes = incrementalSize,
            incrementalExportSizeSectors = incrementalSizeSectors,
            hasStateForIncremental = lastState != null,
            timestamp = System.currentTimeMillis()
        )
    }

    /** Runs one incremental size query, mapping the SDK's "no new data" answer to 0. */
    private inline fun incrementalOrZero(query: () -> Long): Long =
        try {
            query()
        } catch (e: WormException) {
            if (!WormErrorClass.isNoNewData(e)) throw e
            logger.info("No new data since the last incremental export; incremental size is 0")
            0L
        }
}
