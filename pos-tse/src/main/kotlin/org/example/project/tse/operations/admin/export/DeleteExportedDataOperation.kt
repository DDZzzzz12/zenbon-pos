package org.example.project.tse.operations.admin.export

import com.secureflashcard.wormapi.WormException
import com.secureflashcard.wormapi.WormExportTarCallback
import com.secureflashcard.wormapi.WormInformation
import com.secureflashcard.wormapi.WormStore
import com.secureflashcard.wormapi.WormUserId
import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.tse.TseDto
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import org.example.project.tse.models.TseStatus
import org.example.project.tse.operations.admin.BaseTseOperation
import org.example.project.tse.operations.signing.WormErrorClass
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Export everything from the TSE, then delete it — as ONE operation on the TSE thread.
 *
 * `export_deleteStoredData` succeeds only if "a full or incremental export (but not a filtered
 * one) must have been performed previously and no new data must have been generated since then"
 * (SDK `WormStore#export_deleteStoredData`, WormStore.java ~1903-1921). The delete also requires
 * an Admin login, and that login is itself a new signed log entry. A delete that logs in after a
 * separate, unauthenticated export therefore always fails with
 * `WORM_ERROR_EXPORT_UNACKNOWLEDGED_DATA` (rt13 A4, real Swissbit TSE). There is no separate
 * acknowledge call in the SDK: completing an `export_tar` is the acknowledgement.
 *
 * The SDK's documented order is: login Admin → (update time) → export → delete. This operation
 * runs, without releasing the TSE thread in between:
 *
 * 1. `user_login(WORM_USER_ADMIN, pin)`
 * 2. pre-checks from `info()` — a valid time on TSE 2.0, a writable [outputPath] with room for the TAR
 * 3. `export_tar(callback)` — the same full export `FullExportOperation` uses — into a new TAR in
 *    [outputPath], flushed and fsynced
 * 4. archive check — size > 0, a whole number of 512-byte TAR records, equal to the bytes the
 *    callback received, and not smaller than `WormInformation.tarExportSize()` read after the login
 * 5. `export_deleteStoredData()`
 * 6. `user_logout(WORM_USER_ADMIN)` — best effort, always attempted once logged in
 *
 * If any step before 5 fails nothing is deleted, and the [PosException] names the step. A failed
 * export or archive check removes its partial file; the device still holds every entry.
 *
 * The time is deliberately NOT updated here: on TSE 2.0 that needs TimeAdmin, whose login evicts
 * Admin. A TSE 2.0 without a valid time is refused before the export instead.
 *
 * The written TAR is the ONLY remaining copy of the deleted entries. Callers must tell the operator
 * to keep it.
 *
 * Endpoint: DELETE /api/tse/export
 *
 * @param adminPin Admin PIN of the current operator sitting
 * @param outputPath Directory the archive is written to
 * @since 2.0.0
 */
class DeleteExportedDataOperation(
    adminPin: String,
    private val outputPath: String,
) : BaseTseOperation<TseDto.ExportAndDeleteResultDto>(adminPin) {

    companion object {
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
        private const val TAR_RECORD_SIZE = 512L
    }

    override fun performOperation(tseStatus: TseStatus): TseDto.ExportAndDeleteResultDto {
        logger.warn("Export-and-delete requested: the TSE will be exported to $outputPath, then erased")
        val store = getWormStore(tseStatus)

        step(tseStatus, "LOGIN") { loginAsAdmin(store) }
        try {
            return exportThenDelete(tseStatus, store)
        } finally {
            logoutQuietly(store)
        }
    }

    private fun exportThenDelete(tseStatus: TseStatus, store: WormStore): TseDto.ExportAndDeleteResultDto {
        val outputDir = File(outputPath)
        val before = step(tseStatus, "PRECHECK") { store.info().use { DeviceCounts.of(it) } }
        requireDeletable(before, outputDir)

        val archive = step(tseStatus, "EXPORT") { writeArchive(store, outputDir) }
        verifyArchive(archive, expectedBytes = before.exportSizeBytes)

        step(tseStatus, "DELETE") { store.export_deleteStoredData() }
        logger.warn("TSE storage erased after archiving to ${archive.file.absolutePath} (${archive.bytes} bytes)")

        val after = readBackOrNull(store)
        return TseDto.ExportAndDeleteResultDto(
            success = true,
            archiveFileName = archive.file.name,
            archiveFilePath = archive.file.absolutePath,
            archiveSizeBytes = archive.file.length(),
            usedBlocksBefore = before.usedBlocks,
            usedBlocksAfter = after?.usedBlocks,
            exportSizeBytesBefore = before.exportSizeBytes,
            exportSizeBytesAfter = after?.exportSizeBytes,
            timestamp = System.currentTimeMillis(),
            message =
                "TSE exported to ${archive.file.name} and erased. Keep this file: it is the only copy " +
                    "of the deleted entries.",
        )
    }

    /** Refusals given before any file is written or anything is asked of the device beyond `info()`. */
    private fun requireDeletable(before: DeviceCounts, outputDir: File) {
        if (before.isTseV2 && !before.hasValidTime) {
            throw stepFailure(
                "PRECHECK",
                PosErrorCode.TSE_TIME_SYNC_REQUIRED,
                HttpStatusCode.Conflict,
                "TSE 2.0 needs a valid time to delete; run a time update first",
            )
        }
        outputDir.mkdirs()
        if (!outputDir.isDirectory || !outputDir.canWrite()) {
            throw stepFailure(
                "PRECHECK",
                PosErrorCode.TSE_EXPORT_FAILED,
                HttpStatusCode.Conflict,
                "export directory ${outputDir.absolutePath} is not writable",
            )
        }
        if (outputDir.usableSpace < before.exportSizeBytes) {
            throw stepFailure(
                "PRECHECK",
                PosErrorCode.TSE_EXPORT_FAILED,
                HttpStatusCode.InsufficientStorage,
                "export directory has ${outputDir.usableSpace} bytes free, the export needs ${before.exportSizeBytes}",
            )
        }
    }

    /**
     * The full `export_tar`, written and fsynced. A write failure aborts the export through the
     * callback's non-zero return (the SDK contract) rather than by throwing through JNI.
     */
    private fun writeArchive(store: WormStore, outputDir: File): WrittenArchive {
        val file =
            try {
                newArchiveFile(outputDir)
            } catch (e: IOException) {
                throw writeFailure(e)
            }
        var bytes = 0L
        var writeError: IOException? = null
        try {
            FileOutputStream(file).use { fos ->
                store.export_tar(object : WormExportTarCallback() {
                    override fun onNewData(data: ByteArray): Int =
                        try {
                            fos.write(data)
                            bytes += data.size
                            0
                        } catch (e: IOException) {
                            writeError = e
                            1
                        }
                })
                fos.flush()
                fos.fd.sync()
            }
        } catch (e: WormException) {
            file.delete()
            writeError?.let { throw writeFailure(it) }
            throw e
        } catch (e: IOException) {
            file.delete()
            throw writeFailure(e)
        }
        writeError?.let {
            file.delete()
            throw writeFailure(it)
        }
        return WrittenArchive(file, bytes)
    }

    /**
     * A file that did not exist before. An earlier archive is the only copy of what an earlier run
     * deleted, so it must never be overwritten — not even by a second run in the same millisecond.
     */
    private fun newArchiveFile(outputDir: File): File {
        val stem = "tse_export_before_delete_${LocalDateTime.now().format(DATE_FORMATTER)}"
        return generateSequence(0) { it + 1 }
            .map { n -> File(outputDir, if (n == 0) "$stem.tar" else "${stem}_$n.tar") }
            .first { it.createNewFile() }
    }

    private fun verifyArchive(archive: WrittenArchive, expectedBytes: Long) {
        val onDisk = archive.file.length()
        val problem = when {
            archive.bytes <= 0L -> "the TSE delivered no data"
            onDisk != archive.bytes -> "the file holds $onDisk bytes, the TSE delivered ${archive.bytes}"
            onDisk % TAR_RECORD_SIZE != 0L -> "$onDisk bytes is not a whole number of TAR records"
            expectedBytes > 0 && onDisk < expectedBytes -> "the file holds $onDisk bytes, the TSE reported $expectedBytes"
            else -> null
        } ?: return
        archive.file.delete()
        throw stepFailure("VERIFY", PosErrorCode.TSE_EXPORT_FAILED, HttpStatusCode.InternalServerError, problem)
    }

    private fun readBackOrNull(store: WormStore): DeviceCounts? =
        try {
            store.info().use { DeviceCounts.of(it) }
        } catch (e: WormException) {
            logger.warn("Deleted, but the TSE storage could not be read back: ${e.message}")
            null
        }

    private fun logoutQuietly(store: WormStore) {
        try {
            store.user_logout(WormUserId.WORM_USER_ADMIN)
        } catch (e: WormException) {
            logger.debug("Admin logout after export-and-delete was a no-op: ${e.message}")
        }
    }

    /** Runs one device step, turning a device error into a refusal that names the step. */
    private fun <T> step(tseStatus: TseStatus, name: String, block: () -> T): T =
        try {
            block()
        } catch (e: WormException) {
            val errorClass = WormErrorClass.of(e)
            if (errorClass.resetsConnection) tseStatus.handleHardwareError(e)
            throw stepFailure(name, errorClass.code, errorClass.httpStatus, errorClass.messageFor(e), e)
        }

    private fun writeFailure(e: IOException): PosException =
        stepFailure(
            "WRITE",
            PosErrorCode.TSE_EXPORT_FAILED,
            HttpStatusCode.InternalServerError,
            "the archive could not be written: ${e.message}",
            e,
        )

    private fun stepFailure(
        step: String,
        code: PosErrorCode,
        status: HttpStatusCode,
        reason: String,
        cause: Throwable? = null,
    ): PosException =
        PosException(
            code = code,
            httpStatus = status,
            message = "Export-and-delete stopped at step $step: $reason. Nothing was deleted.",
            cause = cause,
        )

    private class WrittenArchive(val file: File, val bytes: Long)

    private class DeviceCounts(
        val usedBlocks: Long,
        val exportSizeBytes: Long,
        val isTseV2: Boolean,
        val hasValidTime: Boolean,
    ) {
        companion object {
            /** Reads every value inside the caller's `use {}`: the native handle dies on close. */
            fun of(info: WormInformation) =
                DeviceCounts(
                    usedBlocks = info.size(),
                    exportSizeBytes = info.tarExportSize(),
                    isTseV2 = info.isTSEv2(),
                    hasValidTime = info.hasValidTime(),
                )
        }
    }
}
