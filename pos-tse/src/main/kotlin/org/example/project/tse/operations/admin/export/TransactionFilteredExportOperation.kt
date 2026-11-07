/**
 * TSE Transaction-Filtered Export Operation.
 *
 * Exports only transactions within a specified transaction number range.
 * Useful for exporting specific business periods or recovering specific transactions.
 *
 * WARNING: This method can be slow for large datasets. Consider incremental export instead.
 *
 * WormAPI Method: export_tar_filtered_transaction() - Line 1486
 *
 * @param adminPin Admin PIN for TSE authentication
 * @param outputPath Directory path for export files (default: "exports")
 * @param startTransaction Start transaction number
 * @param endTransaction End transaction number
 * @param clientId Client ID to filter on; null or blank exports every client of the TSE
 * @since 2.0.0
 */
package org.example.project.tse.operations.admin.export

import com.secureflashcard.wormapi.WormExportTarCallback
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.tse.models.TseStatus
import org.example.project.tse.operations.admin.BaseTseOperation
import org.example.project.tse.utils.FilteredTarExport
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class TransactionFilteredExportOperation(
    adminPin: String,
    private val outputPath: String = "exports",
    private val startTransaction: Long,
    private val endTransaction: Long,
    /** Client to filter on; null or blank = all clients. Never defaulted from TseStatus (F69). */
    private val clientId: String? = null,
) : BaseTseOperation<TseDto.ExportResultDto>(adminPin) {

    companion object {
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }

    /**
     * Perform transaction-filtered TAR export of TSE data.
     *
     * @param tseStatus Current TSE status and configuration
     * @return Result with export file path and metadata
     */
    override fun performOperation(tseStatus: TseStatus): TseDto.ExportResultDto {
        logger.info("Starting TSE transaction-filtered export: TX $startTransaction to $endTransaction")
        logger.warn("NOTE: Transaction-filtered export can be slow for large datasets")

        val exportsDir = File(outputPath)
        if (!exportsDir.exists()) {
            exportsDir.mkdirs()
        }

        val timestamp = LocalDateTime.now().format(DATE_FORMATTER)
        val fileName = "tse_export_tx${startTransaction}_to_tx${endTransaction}_$timestamp.tar"
        val outputFile = File(exportsDir, fileName)

        val store = getWormStore(tseStatus)
        // No Admin login: export_tar only excludes TimeAdmin, whom the startup sequence never
        // leaves logged in. Requiring Admin would force an operator to unlock TSE administration
        // for a backup, and would prevent an unattended scheduled export.

        var bytesWritten = 0L
        val exportStartTime = System.currentTimeMillis()

        // On TSE 2.0 the hardware filtered-export calls are gone: perform a full export and filter
        // the TAR ourselves, reading transactionNumber out of each log message's DER (§7.1). On
        // TSE 1.x the hardware-filtered call is retained unchanged.
        if (store.info().use { it.isTSEv2() }) {
            val fullExport = ByteArrayOutputStream()
            store.export_tar(object : WormExportTarCallback() {
                override fun onNewData(data: ByteArray): Int {
                    fullExport.write(data)
                    return 0
                }
            })
            val filtered = FilteredTarExport.byTransactionNumber(
                fullExport.toByteArray(),
                startTransaction,
                endTransaction,
                // null = no client filter. Never TseStatus.getClientId(): that is the maintenance
                // client, and filtering by it returned zero transactions for a time range and
                // "no data" for TANRs signed under another client (F69).
                clientId = requestedClient(),
            )
            outputFile.writeBytes(filtered)
            bytesWritten = filtered.size.toLong()
        } else {
            FileOutputStream(outputFile).use { fos ->
                store.export_tar_filtered_transaction(
                    startTransaction,
                    endTransaction,
                    // "" = all clients, as the SDK documents (its SWIG wrapper rejects a null
                    // jstring outright). Never TseStatus.getClientId(): that is the maintenance
                    // client, and filtering by it returned zero transactions for a time range and
                    // "no data" for TANRs signed under another client (F69).
                    requestedClient().orEmpty(),
                    object : WormExportTarCallback() {
                        override fun onNewData(data: ByteArray): Int {
                            fos.write(data)
                            bytesWritten += data.size
                            return 0
                        }
                    }
                )
            }
        }

        val exportDuration = System.currentTimeMillis() - exportStartTime
        logger.info("Transaction-filtered export complete: ${bytesWritten / 1024} KB in ${exportDuration}ms")

        return TseDto.ExportResultDto(
            success = true,
            exportType = "filtered_transaction",
            filePath = outputFile.absolutePath,
            fileName = fileName,
            fileSize = outputFile.length(),
            bytesExported = bytesWritten,
            exportDurationMs = exportDuration,
            timestamp = System.currentTimeMillis(),
            allDataExported = true,
            newState = null,
            message = "Transaction-filtered export completed: TX $startTransaction to $endTransaction"
        )
    }

    /** The requested client, or null for "all clients" (absent or blank). */
    private fun requestedClient(): String? = clientId?.takeIf { it.isNotBlank() }
}
