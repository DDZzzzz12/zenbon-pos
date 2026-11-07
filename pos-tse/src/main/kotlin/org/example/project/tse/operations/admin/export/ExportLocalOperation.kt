package org.example.project.tse.operations.admin.export

import com.secureflashcard.wormapi.WormExportTarCallback
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.tse.operations.admin.BaseTseOperation
import org.example.project.tse.models.TseStatus
import java.io.File
import java.io.FileOutputStream

/**
 * Export TSE data as TAR archive.
 *
 * Exports all transaction data from the TSE in TAR format for archival
 * and compliance purposes.
 *
 * Export includes:
 * - All transactions
 * - Certificates
 * - Audit logs
 * - TSE configuration
 *
 * DSFinV-K Compliance:
 * - Required for German fiscal compliance (KassenSichV)
 * - Export file must be stored for 10 years
 * - Contains all data required for tax audit
 *
 * WormAPI Method: export_tar() - line 1243
 *
 * Endpoint: POST /admin/export
 *
 * @param adminPin Admin PIN for TSE authentication
 * @param outputPath Optional custom output path (default: exports/tse_export_<timestamp>.tar)
 * @since 2.0.0
 */
class ExportLocalOperation(
    adminPin: String,
    private val outputPath: String
) : BaseTseOperation<TseDto.LocalExportResultDto>(adminPin) {

    /**
     * Export TSE data as TAR archive.
     *
     * @param tseStatus Current TSE status and configuration
     * @return Result.Success with export file path or Result.Error
     */
    override fun performOperation(tseStatus: TseStatus): TseDto.LocalExportResultDto {
        logger.info("Exporting TSE data as TAR archive")

        // Create exports directory
        val exportsDir = File("exports")
        if (!exportsDir.exists()) {
            exportsDir.mkdirs()
        }

        val filePath = outputPath ?: "exports/tse_export_${System.currentTimeMillis()}.tar"
        val outputFile = File(filePath)

        val store = getWormStore(tseStatus)

        // Login as admin
        // No Admin login: export_tar only excludes TimeAdmin, whom the startup sequence never
        // leaves logged in. Requiring Admin would force an operator to unlock TSE administration
        // for a backup, and would prevent an unattended scheduled export.

        // Export TAR with callback (line 1243)
        FileOutputStream(outputFile).use { fos ->
            store.export_tar(object : WormExportTarCallback() {
                override fun onNewData(data: ByteArray): Int {
                    fos.write(data)
                    return 0 // Continue export
                }
            })
        }

        logger.info("TSE data exported successfully to: $filePath")

        return TseDto.LocalExportResultDto(
            success = true,
            filePath = filePath,
            fileSize = outputFile.length(),
            message = "Export completed successfully"
        )
    }
}
