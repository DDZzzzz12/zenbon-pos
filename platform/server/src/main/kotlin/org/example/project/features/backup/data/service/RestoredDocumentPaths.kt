package org.example.project.features.backup.data.service

import org.slf4j.LoggerFactory
import java.sql.Connection

/**
 * Makes absolute `documents.file_path` values that point into the BACKED-UP storage root relative
 * to it, inside the restore transaction.
 *
 * Current code stores document paths relative to the storage root, but older rows may hold the
 * absolute path of the installation they were written on (`FileStorageHelper.resolveStoredPath`
 * still honours those). The restore extracts the archive's `files/` beneath THIS installation's
 * storage root, so such an absolute row would keep pointing at the original installation's folder:
 * missing on another machine, and refused by `isPathSafe` even where it happens to exist. The backup
 * records its root in `metadata.json` (`documentStoragePath`); stripping exactly that prefix makes the
 * row resolve to the extracted file.
 *
 * A row whose relative form already exists is left alone rather than violating the unique index —
 * the two rows would name one file. Absolute paths outside the backed-up root are left too: their
 * files were never in the archive.
 */
object RestoredDocumentPaths {
    private val logger = LoggerFactory.getLogger(RestoredDocumentPaths::class.java)

    /** Rewrites the rows and returns how many changed. A null/blank [backupStorageRoot] is a no-op. */
    fun apply(
        conn: Connection,
        backupStorageRoot: String?,
    ): Int {
        val root = backupStorageRoot?.trimEnd('/', '\\')?.takeIf { it.isNotBlank() } ?: return 0
        val rewritten = listOf("/", "\\").sumOf { separator -> stripPrefix(conn, root + separator) }
        if (rewritten > 0) {
            logger.info("RestoredDocumentPaths: made {} absolute document path(s) under {} relative", rewritten, root)
        }
        return rewritten
    }

    private fun stripPrefix(
        conn: Connection,
        prefix: String,
    ): Int =
        conn
            .prepareStatement(
                """
                UPDATE documents d SET file_path = substr(d.file_path, ?)
                WHERE left(d.file_path, ?) = ?
                  AND NOT EXISTS (SELECT 1 FROM documents o WHERE o.file_path = substr(d.file_path, ?))
                """.trimIndent(),
            ).use { ps ->
                val start = prefix.length + 1
                ps.setInt(1, start)
                ps.setInt(2, prefix.length)
                ps.setString(3, prefix)
                ps.setInt(4, start)
                ps.executeUpdate()
            }
}
