package org.example.project.features.tse.verification

import io.ktor.http.HttpStatusCode
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import java.io.File
import java.io.IOException

/**
 * Headroom on top of the TSE's reported export size. The export-and-delete logs Admin in before it
 * exports, and that login is one more log entry in the archive than the size read beforehand.
 */
const val TSE_ARCHIVE_HEADROOM_BYTES: Long = 1L shl 20

/**
 * Refuses an export-and-delete whose archive cannot be written to [dir] — checked on the server,
 * before the TSE is asked for anything. A probe file is created and removed, because
 * `File.canWrite` answers for permissions only and misses a read-only mount.
 */
fun requireTseArchiveWritable(dir: File) {
    val writable =
        dir.isDirectory &&
            try {
                File.createTempFile(".tse-write-probe", ".tmp", dir).delete()
            } catch (e: IOException) {
                false
            }
    if (!writable) {
        throw PosException(
            code = PosErrorCode.TSE_EXPORT_FAILED,
            httpStatus = HttpStatusCode.Conflict,
            message =
                "The TSE export folder ${dir.absolutePath} is not writable. Nothing was exported or deleted; " +
                    "fix the export path first.",
        )
    }
}

/**
 * Refuses an export-and-delete when [dir] has less free space than the [exportBytes] the TSE
 * reports plus [TSE_ARCHIVE_HEADROOM_BYTES]. Without it the delete would fail half-way through the
 * export at best, and the operator would learn about a full disk from a TSE error.
 */
fun requireTseArchiveRoom(
    dir: File,
    exportBytes: Long,
) {
    val needed = exportBytes + TSE_ARCHIVE_HEADROOM_BYTES
    val free = dir.usableSpace
    if (free < needed) {
        throw PosException(
            code = PosErrorCode.TSE_EXPORT_FAILED,
            httpStatus = HttpStatusCode.InsufficientStorage,
            message =
                "The TSE export folder ${dir.absolutePath} has $free bytes free; the archive needs $needed. " +
                    "Nothing was exported or deleted.",
        )
    }
}
