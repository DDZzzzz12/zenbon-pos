package org.example.project.utils

import io.ktor.http.HttpStatusCode
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import java.io.File
import java.nio.file.Files

/**
 * File system helpers shared across export services.
 */
object FileSystemUtils {
    /**
     * Ensures [dir] exists and is writable by the current process.
     *
     * Unlike [File.mkdirs] (which silently returns `false` on failure with no diagnostic),
     * this throws a [PosException] with a specific message describing which condition failed:
     *  - path exists but is a file, not a directory
     *  - path exists but is not writable
     *  - directory creation failed (delegating to [Files.createDirectories] for the underlying cause)
     *  - directory was created but is not writable
     *
     * The failure is **400 Bad Request**, not 500: every caller reaches this with an operator-chosen
     * export path out of the DATEV configuration, so an unusable path is bad input rather than a
     * server fault. It used to throw a raw `IOException`, which escaped as an opaque 500 — the exact
     * case a restored production configuration hits, because its Linux export path does not exist on
     * the machine that restored the archive. This matches the blank-path guard in
     * `DatevXmlExportServiceImpl.saveToServerPath`, which already threw
     * `VALIDATION_FAILED`/`BadRequest`.
     */
    fun ensureDirectoryWritable(dir: File) {
        if (dir.exists()) {
            if (!dir.isDirectory) throw notUsable("${dir.absolutePath} exists but is not a directory")
            if (!dir.canWrite()) throw notUsable("${dir.absolutePath} is not writable by the server process")
            return
        }
        try {
            Files.createDirectories(dir.toPath())
        } catch (e: Exception) {
            throw notUsable("Could not create directory ${dir.absolutePath}: ${e.javaClass.simpleName} ${e.message}", e)
        }
        if (!dir.canWrite()) {
            throw notUsable("${dir.absolutePath} created but is not writable by the server process")
        }
    }

    /**
     * Validates an operator-chosen folder setting before it is stored: [path] must be an **absolute**
     * path naming a directory the server can write to — an existing writable directory, or one it
     * can create (it is created here, as [ensureDirectoryWritable] does). Every failure is a reasoned
     * 400 naming the path.
     *
     * Used when the DATEV export path and the backup path are saved (SET-05). A relative path is
     * refused rather than resolved: it would land under whatever the server's working directory
     * happens to be — a different folder after a reinstall or a service-unit change, and silently so,
     * which is exactly where backups must not go missing. A path naming an existing file is refused
     * by [ensureDirectoryWritable].
     */
    fun ensureAbsoluteWritableDirectory(path: String) {
        val dir = File(path.trim())
        if (!dir.isAbsolute) throw notUsable("$path is not an absolute path; give the full folder path")
        ensureDirectoryWritable(dir)
    }

    private fun notUsable(
        message: String,
        cause: Throwable? = null,
    ) = PosException(
        PosErrorCode.VALIDATION_FAILED,
        HttpStatusCode.BadRequest,
        message,
        cause = cause,
    )
}
