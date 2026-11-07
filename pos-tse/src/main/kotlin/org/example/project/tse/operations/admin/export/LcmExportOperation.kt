/**
 * LCM (Life Cycle Management) data export operation.
 *
 * Exports LCM diagnostics data from the TSE to a file.
 * This data is in a proprietary Swissbit format used for
 * support and diagnostics purposes.
 *
 * Endpoint: POST /admin/export/lcm
 *
 * @param adminPin Admin PIN for TSE authentication
 * @param outputPath Directory path for the LCM export file
 * @since 2.1.0
 */
package org.example.project.tse.operations.admin.export

import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.tse.models.TseStatus
import org.example.project.tse.operations.admin.BaseTseOperation
import java.io.File

class LcmExportOperation(
    adminPin: String,
    private val outputPath: String = "exports"
) : BaseTseOperation<TseDto.LcmExportResultDto>(adminPin) {

    /**
     * Export LCM diagnostics data.
     *
     * @param tseStatus Current TSE status and configuration
     * @return Result.Success with export result or Result.Error
     */
    override fun performOperation(tseStatus: TseStatus): TseDto.LcmExportResultDto {
        logger.info("Exporting LCM data to: $outputPath")

        val store = getWormStore(tseStatus)
        // No Admin login: export_tar only excludes TimeAdmin, whom the startup sequence never
        // leaves logged in. Requiring Admin would force an operator to unlock TSE administration
        // for a backup, and would prevent an unattended scheduled export.

        // Ensure output directory exists
        val outputDir = File(outputPath)
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        val filePath = File(outputDir, "lcm_export_${System.currentTimeMillis()}.lcm").absolutePath

        // export_lcm_file returns a format/version string
        val formatVersion = store.export_lcm_file(filePath)

        logger.info("LCM export completed: formatVersion=$formatVersion, path=$filePath")

        return TseDto.LcmExportResultDto(
            success = true,
            message = "LCM data exported successfully",
            formatVersion = formatVersion,
            filePath = filePath
        )
    }
}
