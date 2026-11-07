package org.example.project.features.backup.domain.routes

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveMultipart
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.coroutineScope
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException
import java.io.File
import java.io.IOException

/**
 * Receives the backup ZIP of a multipart restore upload into a temporary file, refusing anything
 * over the cap with **413 `UPLOAD_TOO_LARGE`** and a sentence naming the limit.
 *
 * Shared by `POST /api/v1/backup/restore` and `POST /api/setup/restore-backup`, so both honour the
 * same configurable cap (`BACKUP_CONFIG.maxUploadBytes`, default 2 GiB). v1.0.10's setup restore
 * had a fixed 50 MB cap and answered a real 110 MB backup with a bare 500: Ktor's multipart parser
 * signals an exceeded `formFieldLimit` with a plain `IOException`, which `StatusPages` cannot tell
 * apart from a server fault.
 *
 * The cap is enforced by counting the bytes copied, not by the parser, so the refusal is ours and
 * carries our status. A declared `Content-Length` over the cap is refused before anything is read.
 * The parser's own limit stays set, a little above the cap, as the bound on non-file form fields.
 */
internal object BackupUploadReceiver {
    /** The multipart part names a backup ZIP may arrive under. */
    private val PART_NAMES = setOf("backup", "file")

    /** Room for boundaries and part headers on top of the file itself. */
    private const val MULTIPART_OVERHEAD_BYTES = 1L * 1024 * 1024

    private const val CHUNK_BYTES = 64 * 1024

    /**
     * Ktor's wordings when its parser limit trips before our count does: a part that declares its own
     * length names `formFieldLimit`; the boundary scanner, which reads ahead of the copy, says
     * "Limit of N bytes exceeded while searching for …". Missing the second turned a chunked upload
     * more than [MULTIPART_OVERHEAD_BYTES] over the cap into a bare 500 (QA RST-11, 2026-10-06).
     */
    private val KTOR_LIMIT_MESSAGES = listOf(Regex("formFieldLimit"), Regex("""Limit of \d+ bytes exceeded"""))

    /** The received ZIP. The caller owns [file] and must delete it. */
    data class Received(
        val file: File,
        val originalFileName: String?,
    )

    /**
     * Streams the first backup part of [call] into a temp file of at most [limitBytes] bytes.
     * Returns null when the request carries no backup part. Throws [PosException] 413 when the
     * upload exceeds [limitBytes]; any partially written temp file is deleted first.
     */
    suspend fun receive(
        call: ApplicationCall,
        limitBytes: Long,
    ): Received? {
        require(limitBytes > 0) { "limitBytes must be positive" }
        call.request.contentLength()?.let { declared ->
            if (declared > limitBytes + MULTIPART_OVERHEAD_BYTES) throw tooLarge(limitBytes)
        }

        var received: Received? = null
        try {
            // Ktor launches its multipart parser as a child of the calling coroutine. Without this
            // scope that child is the call's own job: when our count refuses first, the parser keeps
            // scanning the rest of the body, trips its own limit, and its IOException fails the whole
            // call as a 500 over our 413 (QA RST-11). Scoped, our refusal cancels the parser, and a
            // parser failure is rethrown here, where it can be mapped.
            coroutineScope {
                call.receiveMultipart(formFieldLimit = parserLimit(limitBytes)).forEachPart { part ->
                    try {
                        if (received == null && part is PartData.FileItem && part.name in PART_NAMES) {
                            val target = File.createTempFile("backup-restore-", ".zip")
                            received = Received(target, part.originalFileName)
                            copyCapped(part.provider(), target, limitBytes)
                        }
                    } finally {
                        part.dispose()
                    }
                }
            }
        } catch (e: IOException) {
            received?.file?.delete()
            if (isParserLimit(e)) throw tooLarge(limitBytes)
            throw e
        } catch (e: Throwable) {
            received?.file?.delete()
            throw e
        }
        return received
    }

    private fun isParserLimit(e: IOException): Boolean {
        val message = e.message ?: return false
        return KTOR_LIMIT_MESSAGES.any { it.containsMatchIn(message) }
    }

    private suspend fun copyCapped(
        source: ByteReadChannel,
        target: File,
        limitBytes: Long,
    ) {
        val buffer = ByteArray(CHUNK_BYTES)
        var total = 0L
        target.outputStream().use { out ->
            while (true) {
                val read = source.readAvailable(buffer, 0, buffer.size)
                if (read == -1) break
                total += read
                if (total > limitBytes) throw tooLarge(limitBytes)
                out.write(buffer, 0, read)
            }
        }
    }

    private fun parserLimit(limitBytes: Long): Long =
        if (limitBytes > Long.MAX_VALUE - MULTIPART_OVERHEAD_BYTES) Long.MAX_VALUE else limitBytes + MULTIPART_OVERHEAD_BYTES

    private fun tooLarge(limitBytes: Long) =
        PosException(
            PosErrorCode.UPLOAD_TOO_LARGE,
            HttpStatusCode.PayloadTooLarge,
            "The backup file exceeds the upload limit of ${humanSize(limitBytes)} ($limitBytes bytes). " +
                "Raise maxUploadBytes in the backup configuration, or restore from a file on the server.",
        )

    private fun humanSize(bytes: Long): String {
        val kib = 1024L
        return when {
            bytes >= kib * kib * kib && bytes % (kib * kib * kib) == 0L -> "${bytes / (kib * kib * kib)} GB"
            bytes >= kib * kib && bytes % (kib * kib) == 0L -> "${bytes / (kib * kib)} MB"
            bytes >= kib && bytes % kib == 0L -> "${bytes / kib} KB"
            else -> "$bytes bytes"
        }
    }
}
