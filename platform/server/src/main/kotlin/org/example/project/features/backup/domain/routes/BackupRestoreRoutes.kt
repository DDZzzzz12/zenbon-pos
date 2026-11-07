/**
 * HTTP routes for database backup and restore operations.
 *
 * Provides REST API endpoints for:
 * - Creating database backups (JDBC COPY protocol, ZIP archive format)
 * - Restoring database from backup ZIP files
 * - Managing backup configuration settings
 *
 * All endpoints require ADMIN or OWNER role for security.
 *
 * Migrated to PosException-throws style in Stage B Batch 11. Service throws
 * PosException directly; the global StatusPages plugin emits problem+json.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.backup.domain.routes

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.example.project.core.domain.dto.backup.BackupRestoreDto
import org.example.project.core.domain.dto.setup.RestoredPathsDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.backup.data.service.BackupCopyCoordinator
import org.example.project.features.backup.domain.service.BackupRestoreService
import org.example.project.features.backup.domain.service.RestoredPathReviewService
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val logger = LoggerFactory.getLogger("BackupRestoreRoutes")

/**
 * Registers backup and restore routes.
 */
fun Routing.backupRestoreRoutes() {
    val backupService by inject<BackupRestoreService>()
    val restoredPathReview by inject<RestoredPathReviewService>()
    val copyCoordinator by inject<BackupCopyCoordinator>()

    authenticate("auth-jwt") {
        route("/api/v1/backup") {
            /**
             * Create a backup and download it.
             *
             * Description: Requires ADMIN role. Dumps every table over the JDBC COPY protocol into a ZIP of CSVs and streams it back as pos_backup_{timestamp}.zip. The request body is optional — omitting it takes the default scope. Large databases make this slow, and nothing is written to the server's disk.
             *
             * Tag: backup
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 500 The backup could not be produced.
             */
            post("/create") {
                logger.info("Creating database backup...")

                val request =
                    try {
                        call.receiveNullable<BackupRestoreDto.CreateBackupRequestDto>()
                    } catch (e: Exception) {
                        null
                    }

                val backupData = backupService.createBackup(request)
                val timestamp =
                    LocalDateTime.now().format(
                        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"),
                    )
                val filename = "pos_backup_$timestamp.zip"

                logger.info("Backup created successfully: $filename (${backupData.size} bytes)")

                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment
                        .withParameter(
                            ContentDisposition.Parameters.FileName,
                            filename,
                        ).toString(),
                )
                call.respondBytes(
                    bytes = backupData,
                    contentType = ContentType.Application.Zip,
                )
            }

            /**
             * Restore the database from an uploaded backup.
             *
             * Description: Requires ADMIN role. Destructive — it replaces all existing data. Send the ZIP as a multipart part named `backup` or `file`; it is streamed to a temporary file rather than buffered, so multi-gigabyte archives do not exhaust memory, and the temporary file is deleted afterwards. The upload cap is the backup configuration's `maxUploadBytes` (default 2 GiB); a larger upload is refused with 413 `UPLOAD_TOO_LARGE` naming the limit. When the restored configuration carries folder paths outside this installation, `restoredPathsNeedingReview` lists them and file writes to them are paused until reviewed via `/api/v1/backup/restored-paths`.
             *
             * Tag: backup
             * Response: 400 The request carried no backup file part.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 413 The upload exceeds the configured upload cap.
             * Response: 422 The archive is not a valid POS backup.
             * Response: 500 The restore failed partway; the database may be inconsistent.
             */
            post("/restore") {
                logger.warn("Database restore requested - this will replace all data!")

                val upload =
                    BackupUploadReceiver.receive(call, backupService.getMaxUploadBytes())
                        ?: throw PosException(
                            PosErrorCode.INVALID_REQUEST,
                            HttpStatusCode.BadRequest,
                            "No backup file provided. Upload a .zip backup file with field name 'backup'",
                        )

                try {
                    logger.info("Restoring from backup: ${upload.originalFileName} (${upload.file.length()} bytes)")
                    val result = backupService.restoreBackup(upload.file)
                    logger.info("Database restore completed: ${result.message}")
                    call.respond(HttpStatusCode.OK, result)
                } finally {
                    upload.file.delete()
                }
            }

            /**
             * Get the backup configuration.
             *
             * Description: Requires ADMIN role. The scheduled-backup settings — target directory, retention and upload limit.
             *
             * Tag: backup
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             */
            get("/config") {
                val shopId = 1L
                val dto = backupService.getBackupConfig(shopId)
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * Update the backup configuration.
             *
             * Description: Requires ADMIN role. Changes take effect for the next scheduled run. When the backup path is awaiting the post-restore review, storing a path of this installation resolves that entry; another foreign path stays under review and is listed as now configured.
             *
             * Tag: backup
             * Response: 400 The request body failed validation, or defaultBackupPath is not absolute, names a file rather than a directory, cannot be created, or is not writable.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             */
            put("/config") {
                val request = call.receive<BackupRestoreDto.UpdateBackupConfigDto>()
                val shopId = 1L
                val dto = backupService.updateBackupConfig(shopId, request)
                logger.info("Backup configuration updated")
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * Create a backup on the server.
             *
             * Description: Requires ADMIN role. Writes the archive to a path on the server instead of streaming it to the caller — the route the scheduled backup and any NAS target use. Omitting savePath uses the configured default directory. When a second copy target is configured the archive is copied there afterwards; the outcome is reported in `copyStatus` and `copyMessage`, and a failed copy never fails the request.
             *
             * Tag: backup
             * Response: 400 The body failed validation, or the save path is not writable.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 500 The backup could not be produced or written.
             */
            post("/create-to-server") {
                val request = call.receive<BackupRestoreDto.CreateBackupToServerRequestDto>()
                logger.info("Creating backup to server path: ${request.savePath ?: "default"}")
                val dto = backupService.createBackupToServer(request)
                logger.info("Backup saved to: ${dto.filePath}")
                // The second copy never fails the request: its outcome travels in the response.
                val copy = copyCoordinator.copyAfterBackup(1L, File(dto.filePath))
                call.respond(HttpStatusCode.OK, dto.copy(copyStatus = copy?.status, copyMessage = copy?.message))
            }

            /**
             * Restore from a backup already on the server.
             *
             * Description: Requires ADMIN role. Destructive — it replaces all existing data. Takes a server-side file path, so a large archive need not be uploaded through the browser. Restored folder paths outside this installation are reported in `restoredPathsNeedingReview` and paused, as for the upload restore.
             *
             * Tag: backup
             * Response: 400 The body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 404 No backup file at that path.
             * Response: 422 The archive is not a valid POS backup.
             * Response: 500 The restore failed partway; the database may be inconsistent.
             */
            post("/restore-from-server") {
                @kotlinx.serialization.Serializable
                data class RestoreFromServerRequest(
                    val filePath: String,
                )

                val request = call.receive<RestoreFromServerRequest>()
                logger.warn("Restoring from server file: ${request.filePath}")
                val dto = backupService.restoreFromServerFile(request.filePath)
                logger.info("Database restored from: ${request.filePath}")
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * Browse server directories.
             *
             * Description: Requires ADMIN role. Lists sub-directories of the given `path` so an operator can pick a backup target in the UI without typing a path. Omitting `path` starts at the default location.
             *
             * Tag: backup
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 404 No such directory on the server.
             */
            get("/directories") {
                val path = call.request.queryParameters["path"]?.takeIf { it != "null" }
                val dto = backupService.listDirectory(path)
                call.respond(HttpStatusCode.OK, dto)
            }

            /**
             * List restored paths awaiting review.
             *
             * Description: Requires BACKUP_RESTORE. After a restore, the configured folder paths (DATEV export folder, backup folder, document storage) that lie outside this installation's app-data directory — typically the original shop's folders when the backup came from another machine or install. While any is listed (`pending = true`), scheduled DATEV exports and backups that write there are skipped, explicit exports and server-side backups into it answer 409 RESTORED_PATHS_NEED_REVIEW, and documents use this installation's default folder. Each entry carries the restored path, the default that "use default" would switch to, and `keepAllowed` — false for DOCUMENT_STORAGE_PATH, whose restored files were extracted into this installation's default folder, so only USE_DEFAULT is offered for it.
             *
             * Tag: backup
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller does not hold BACKUP_RESTORE.
             */
            get("/restored-paths") {
                call.respond(HttpStatusCode.OK, restoredPathReview.getReview())
            }

            /**
             * Resolve restored paths.
             *
             * Description: Requires BACKUP_RESTORE. Decides per listed path: KEEP leaves the restored path as configured (refused for DOCUMENT_STORAGE_PATH, see the GET), USE_DEFAULT switches it to this installation's default (exports/backups folder under the app-data directory; the document path goes back to the built-in default). Settings that are not listed are ignored, so repeating a request is harmless. Once every listed path is decided the pause lifts and the scheduler resumes — a DATEV catch-up then exports the periods it skipped.
             *
             * Tag: backup
             * Response: 400 The request is empty, decides a setting twice, or asks to KEEP the document path.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller does not hold BACKUP_RESTORE.
             */
            post("/restored-paths") {
                val request = call.receive<RestoredPathsDto.ResolveRequestDto>()
                call.respond(HttpStatusCode.OK, restoredPathReview.resolve(request))
            }

            /**
             * List backups on the server.
             *
             * Description: Requires ADMIN role. The archives found in `directory`, or in the configured default directory when it is omitted, with their size and timestamp.
             *
             * Tag: backup
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not an ADMIN.
             * Response: 404 No such directory on the server.
             */
            get("/list") {
                val directory = call.request.queryParameters["directory"]?.takeIf { it != "null" }
                val dto = backupService.listBackups(directory)
                call.respond(HttpStatusCode.OK, dto)
            }
        }
    }
}
