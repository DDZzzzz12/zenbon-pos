/**
 * TSE Full Export Operation.
 *
 * Performs a complete TAR export of all TSE transaction data for
 * DSFinV-K compliance (KassenSichV / TR-03153).
 *
 * The exported TAR archive contains:
 * - info.csv: TSE information and metadata
 * - cert.pem: TSE certificates
 * - Transaction logs with cryptographic signatures
 * - Audit trails
 *
 * WormAPI Method: export_tar() - Line 1241
 *
 * @param adminPin Admin PIN for TSE authentication
 * @param outputPath Directory path for export files (default: "exports")
 * @since 2.0.0
 */
package org.example.project.tse.operations.admin.export

import com.secureflashcard.wormapi.WormExportTarCallback
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.tse.models.TseStatus
import org.example.project.tse.operations.admin.BaseTseOperation
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class FullExportOperation(
    adminPin: String,
    private val outputPath: String = "exports"
) : BaseTseOperation<TseDto.ExportResultDto>(adminPin) {

    companion object {
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }

    /**
     * Perform full TAR export of all TSE data.
     *
     * @param tseStatus Current TSE status and configuration
     * @return Result with export file path and metadata
     */
    override fun performOperation(tseStatus: TseStatus): TseDto.ExportResultDto {
        logger.info("Starting TSE full export")

        // Ensure exports directory exists
        val exportsDir = File(outputPath)
        if (!exportsDir.exists()) {
            exportsDir.mkdirs()
            logger.info("Created exports directory: $outputPath")
        }

        // Generate filename with timestamp
        val timestamp = LocalDateTime.now().format(DATE_FORMATTER)
        val fileName = "tse_full_export_$timestamp.tar"
        val outputFile = File(exportsDir, fileName)

        val store = getWormStore(tseStatus)
        // No Admin login: export_tar only excludes TimeAdmin, whom the startup sequence never
        // leaves logged in. Requiring Admin would force an operator to unlock TSE administration
        // for a backup, and would prevent an unattended scheduled export.

        // Get pre-export info
        val info = store.info()
        val tarExportSize = info.use { it.tarExportSize() }
        logger.info("Pre-export: estimated size=$tarExportSize bytes")

        // Perform export with progress tracking
        var bytesWritten = 0L
        val startTime = System.currentTimeMillis()

        FileOutputStream(outputFile).use { fos ->
            store.export_tar(object : WormExportTarCallback() {
                override fun onNewData(data: ByteArray): Int {
                    fos.write(data)
                    bytesWritten += data.size
                    if (bytesWritten % (1024 * 1024) == 0L) {
                        logger.debug("Export progress: ${bytesWritten / 1024 / 1024} MB")
                    }
                    return 0
                }
            })
        }

        val exportDuration = System.currentTimeMillis() - startTime
        logger.info("Full export complete: ${bytesWritten / 1024} KB in ${exportDuration}ms to ${outputFile.absolutePath}")

        return TseDto.ExportResultDto(
            success = true,
            exportType = "full",
            filePath = outputFile.absolutePath,
            fileName = fileName,
            fileSize = outputFile.length(),
            bytesExported = bytesWritten,
            exportDurationMs = exportDuration,
            timestamp = System.currentTimeMillis(),
            allDataExported = true,
            newState = null,
            message = "Full export completed successfully"
        )
    }
}
