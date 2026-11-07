package org.example.project.features.scheduler.domain.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.example.project.core.domain.dto.scheduler.SchedulerDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.scheduler.domain.service.ScheduledJobService
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject
import java.util.UUID

fun Route.scheduledJobRoutes() {
    val service by inject<ScheduledJobService>()

    authenticate("auth-jwt") {
        route("/api/scheduled-jobs") {
            /**
             * List scheduled jobs.
             *
             * Description: Requires MANAGER role. Every background job with its cadence, enabled flag and last run.
             *
             * Tag: scheduled-jobs
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            get {
                call.respond(HttpStatusCode.OK, service.list())
            }

            /**
             * List the available job types.
             *
             * Description: Requires MANAGER role. The job kinds this server knows how to run, which the UI uses to label and group jobs.
             *
             * Tag: scheduled-jobs
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             */
            get("/types") {
                call.respond(HttpStatusCode.OK, service.listTypes())
            }

            /**
             * Get a scheduled job.
             *
             * Description: Requires MANAGER role. The job's full configuration and current state.
             *
             * Tag: scheduled-jobs
             * Path: id Scheduled job identifier, a UUID.
             * Response: 400 id is missing, or is not a valid UUID.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No scheduled job with that id.
             */
            get("/{id}") {
                val id = parseId(call.parameters["id"])
                call.respond(HttpStatusCode.OK, service.getDetail(id))
            }

            /**
             * Run a scheduled job now.
             *
             * Description: Requires MANAGER role. Triggers the job immediately, outside its schedule, and records the run in the execution history like any other. A disabled job can still be run manually.
             *
             * Tag: scheduled-jobs
             * Path: id Scheduled job identifier, a UUID.
             * Response: 400 id is missing, or is not a valid UUID.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No scheduled job with that id.
             * Response: 409 The job is already running.
             */
            post("/{id}/run") {
                val id = parseId(call.parameters["id"])
                call.respond(HttpStatusCode.OK, service.triggerManualRun(id))
            }

            /**
             * Enable or disable a scheduled job.
             *
             * Description: Requires MANAGER role. Disabling stops the automatic schedule; the job can still be run by hand.
             *
             * Tag: scheduled-jobs
             * Path: id Scheduled job identifier, a UUID.
             * Response: 400 id is missing or not a valid UUID, or the body failed validation.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No scheduled job with that id.
             */
            put("/{id}/enabled") {
                val id = parseId(call.parameters["id"])
                val body = call.receive<SchedulerDto.SetJobEnabledRequestDto>()
                call.respond(HttpStatusCode.OK, service.setEnabled(id, body.enabled))
            }

            /**
             * List a job's execution history.
             *
             * Description: Requires MANAGER role. Past runs with their outcome and duration, most recent first. Pages with `limit` (default 50) and `offset` (default 0).
             *
             * Tag: scheduled-jobs
             * Path: id Scheduled job identifier, a UUID.
             * Response: 400 id is missing, or is not a valid UUID.
             * Response: 401 Missing or invalid bearer token.
             * Response: 403 Caller is not a MANAGER.
             * Response: 404 No scheduled job with that id.
             */
            get("/{id}/executions") {
                val id = parseId(call.parameters["id"])
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
                call.respond(HttpStatusCode.OK, service.listExecutions(id, limit, offset))
            }
        }
    }
}

private fun parseId(raw: String?): UUID =
    raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            "invalid id",
        )
