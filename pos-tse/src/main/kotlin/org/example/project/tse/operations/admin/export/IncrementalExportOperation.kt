/**
 * TSE Incremental Export Operation.
 *
 * Exports only new data since the last export. Much faster than full export
 * for regular backups. The returned state must be saved for the next
 * incremental export.
 *
 * WormAPI Method: export_tar_incremental_ex() - Line 1398
 *
 * @param adminPin Admin PIN for TSE authentication
 * @param outputPath Directory path for export files (default: "exports")
 * @param lastState Previous incremental export state (null for first export)
 * @param maxExportSize Maximum export size in bytes (0 = unlimited)
 * @since 2.0.0
 */
package org.example.project.tse.operations.admin.export

import com.secureflashcard.wormapi.WormException
import com.secureflashcard.wormapi.WormExportTarIncrementalCallback
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.tse.models.TseStatus
import org.example.project.tse.operations.admin.BaseTseOperation
import org.example.project.tse.operations.signing.WormErrorClass
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

class IncrementalExportOperation(
    adminPin: String,
    private val outputPath: String = "exports",
    private val lastState: ByteArray? = null,
    private val maxExportSize: Long = 0
) : BaseTseOperation<TseDto.ExportResultDto>(adminPin) {

    companion object {
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }

    /**
     * Perform incremental TAR export of new TSE data since last export.
     *
     * @param tseStatus Current TSE status and configuration
     * @return Result with export file path, new state, and completion status
     */
    override fun performOperation(tseStatus: TseStatus): TseDto.ExportResultDto {
        logger.info("Starting TSE incremental export (lastState=${lastState != null}, maxSize=$maxExportSize)")

        val exportsDir = File(outputPath)
        if (!exportsDir.exists()) {
            exportsDir.mkdirs()
        }

        val timestamp = LocalDateTime.now().format(DATE_FORMATTER)
        val fileName = "tse_incremental_export_$timestamp.tar"
        val outputFile = File(exportsDir, fileName)

        val store = getWormStore(tseStatus)
        // No Admin login: export_tar only excludes TimeAdmin, whom the startup sequence never
        // leaves logged in. Requiring Admin would force an operator to unlock TSE administration
        // for a backup, and would prevent an unattended scheduled export.

        var bytesWritten = 0L
        val startTime = System.currentTimeMillis()

        val response = try {
            FileOutputStream(outputFile).use { fos ->
                store.export_tar_incremental_ex(
                    lastState,
                    maxExportSize,
                    object : WormExportTarIncrementalCallback() {
                        override fun onNewData(data: ByteArray, processedBlocks: Long, totalBlocks: Long): Int {
                            fos.write(data)
                            bytesWritten += data.size
                            val progress = if (totalBlocks > 0) {
                                (processedBlocks * 100 / totalBlocks).toInt()
                            } else 0
                            if (progress % 10 == 0 && processedBlocks > 0) {
                                logger.debug("Export progress: $progress% ($processedBlocks / $totalBlocks blocks)")
                            }
                            return 0
                        }
                    }
                )
            }
        } catch (e: WormException) {
            // The output file was opened before the SDK call, so whatever the outcome it is empty
            // or partial here: never leave it in the export folder (F40 / RT2-B2).
            deleteQuietly(outputFile)
            // Handle special case: no new data to export
            if (WormErrorClass.isNoNewData(e) || e.message?.contains("INCREMENTAL_EXPORT_NO_DATA") == true) {
                logger.info("No new data to export since last incremental export")
                return TseDto.ExportResultDto(
                    success = true,
                    exportType = "incremental",
                    filePath = null,
                    fileName = null,
                    fileSize = 0,
                    bytesExported = 0,
                    exportDurationMs = 0,
                    timestamp = System.currentTimeMillis(),
                    allDataExported = true,
                    newState = lastState?.let { Base64.getEncoder().encodeToString(it) },
                    message = "No new data to export"
                )
            }
            throw e
        } catch (e: Throwable) {
            deleteQuietly(outputFile)
            throw e
        }

        val exportDuration = System.currentTimeMillis() - startTime
        val allDataExported = response.allDataExported()
        val newState = response.newState()

        logger.info("Incremental export complete: ${bytesWritten / 1024} KB in ${exportDuration}ms, allData=$allDataExported")

        return TseDto.ExportResultDto(
            success = true,
            exportType = "incremental",
            filePath = outputFile.absolutePath,
            fileName = fileName,
            fileSize = outputFile.length(),
            bytesExported = bytesWritten,
            exportDurationMs = exportDuration,
            timestamp = System.currentTimeMillis(),
            allDataExported = allDataExported,
            newState = newState?.let { Base64.getEncoder().encodeToString(it) },
            message = if (allDataExported) {
                "Incremental export completed - all data exported"
            } else {
                "Incremental export completed - more data available"
            }
        )
    }

    /** Best effort: a failed delete is logged, never allowed to mask the export's own outcome. */
    private fun deleteQuietly(file: File) {
        val deleted = runCatching { !file.exists() || file.delete() }.getOrDefault(false)
        if (!deleted) logger.warn("Could not delete incomplete export file ${file.absolutePath}")
    }
}
