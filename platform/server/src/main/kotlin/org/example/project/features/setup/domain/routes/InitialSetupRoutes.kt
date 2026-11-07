/**
 * REST API routes for Initial Setup operations.
 *
 * Provides unauthenticated endpoints for checking system initialization status
 * and performing atomic complete setup. These endpoints do not require JWT
 * authentication because no user account exists before setup.
 *
 * Migrated to PosException-throws style in Stage B Batch 4. Service throws
 * PosException directly; the global StatusPages plugin emits problem+json.
 */
package org.example.project.features.setup.domain.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.backup.domain.routes.BackupUploadReceiver
import org.example.project.features.backup.domain.service.BackupRestoreService
import org.example.project.features.setup.domain.service.InitialSetupService
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("InitialSetupRoutes")

/**
 * Configures initial setup API routes.
 *
 * Three unauthenticated endpoints for system initialization:
 * - GET /api/setup/status — check if system needs initialization
 * - POST /api/setup/initialize — atomic complete setup
 * - POST /api/setup/restore-backup — restore from backup during setup
 *
 * These endpoints are intentionally unauthenticated because no user
 * accounts exist before initial setup is completed.
 */
fun Route.initialSetupRoutes() {
    val service by inject<InitialSetupService>()
    val backupService by inject<BackupRestoreService>()

    route("/api/setup") {
        /**
         * Check whether the system is initialized.
         *
         * Description: Public and unauthenticated on purpose — a fresh install has no accounts yet, so the setup wizard must be able to ask this before anyone can log in. It reports only whether setup has run, nothing about fiscal state.
         *
         * Tag: setup
         */
        get("/status") {
            logger.debug("Checking system initialization status")
            val status = service.checkInitializationStatus()
            call.respond(HttpStatusCode.OK, status)
        }

        /**
         * Check whether the install can take an order.
         *
         * Description: Requires MANAGER role — unlike the public status endpoint next to it, this one reports fiscal terminal and TSE commissioning state, and exposing that anonymously would be reconnaissance for the tampering the TSE exists to prevent.
         *
         * Tag: setup
         * Response: 401 Missing or invalid bearer token.
         * Response: 403 Caller is not a MANAGER.
         */
        get("/readiness") {
            logger.debug("Checking setup readiness")
            val readiness = service.checkReadiness()
            call.respond(HttpStatusCode.OK, readiness)
        }

        /**
         * Initialize the system.
         *
         * Description: Public and unauthenticated, because it is what creates the first account. One atomic call writes the owner account and the shop details; it either completes fully or changes nothing.
         *
         * Tag: setup
         * Response: 400 The request body failed validation.
         * Response: 409 The system is already initialized.
         */
        post("/initialize") {
            logger.info("Received complete setup request")
            val request = call.receive<InitialSetupDto.CompleteSetupRequestDto>()
            val response = service.executeCompleteSetup(request)
            logger.info("Complete setup finished successfully")
            call.respond(HttpStatusCode.Created, response)
        }

        /**
         * Restore a backup during initial setup.
         *
         * Description: Public and unauthenticated, but only while the system is uninitialized — once setup has run this refuses with 409, so it can never overwrite a live shop. Send the backup ZIP as a multipart part named `file` or `backup`; it is streamed to a temporary file, deleted afterwards. The upload cap is the backup configuration's `maxUploadBytes` (default 2 GiB, which a fresh install has); a larger upload is refused with 413 `UPLOAD_TOO_LARGE` naming the limit.
         *
         * Tag: setup
         * Response: 400 The request carried no backup file part.
         * Response: 409 The system is already initialized.
         * Response: 413 The upload exceeds the configured upload cap.
         */
        post("/restore-backup") {
            // Guard: only allow if system is not initialized
            val status = service.checkInitializationStatus()
            if (status.isInitialized) {
                logger.warn("Restore from backup rejected — system already initialized")
                throw PosException(
                    PosErrorCode.ALREADY_PROVISIONED,
                    HttpStatusCode.Conflict,
                    "System is already initialized. Restore from backup is only available during initial setup.",
                )
            }

            logger.info("Restore from backup requested during initial setup")

            val upload =
                BackupUploadReceiver.receive(call, backupService.getMaxUploadBytes())
                    ?: throw PosException(
                        PosErrorCode.INVALID_REQUEST,
                        HttpStatusCode.BadRequest,
                        "No backup file provided. Upload a .zip backup file with field name 'file'.",
                    )

            try {
                logger.info("Restoring from backup during setup: ${upload.originalFileName} (${upload.file.length()} bytes)")
                val result = backupService.restoreBackup(upload.file)
                logger.info("Setup restore completed: ${result.message}")
                call.respond(HttpStatusCode.OK, result)
            } finally {
                upload.file.delete()
            }
        }
    }
}
