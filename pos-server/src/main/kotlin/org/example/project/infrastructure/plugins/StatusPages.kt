package org.example.project.infrastructure.plugins

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.requestvalidation.RequestValidationException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import org.example.project.core.error.FieldError
import org.example.project.core.error.PosErrorCode
import org.example.project.core.error.ProblemDetail
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.errors.ProblemDetailFactory
import org.slf4j.LoggerFactory
import org.slf4j.MDC

private val logger = LoggerFactory.getLogger("StatusPages")

/** Receives every 4xx; logback.xml routes the `rejected` logger to rejected.log only. */
private val rejectedLogger = LoggerFactory.getLogger("rejected")
private val problemJson = Json { encodeDefaults = true }
private val PROBLEM_JSON: ContentType = ContentType("application", "problem+json")

/**
 * Configures StatusPages to emit RFC 7807 ProblemDetail bodies for thrown
 * exceptions, with traceId/code/statusCode MDC keys (set here, cleared in
 * finally). A 5xx is logged at ERROR, which the dedicated error.log appender
 * (logback.xml — Task 8) routes to logs/error.log; a 4xx is logged at INFO on
 * the dedicated `rejected` logger (rejected.log) — see [logOutcome].
 */
fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<PosException> { call, cause ->
            // `cause.fields` is the only path by which a handler can send a machine-readable
            // discriminator alongside the prose. It used to be dropped here, which made the
            // property dead on arrival; nothing populated it, so passing it through changes no
            // existing response.
            respondProblem(call, cause.code, cause.httpStatus, cause.message, cause, cause.fields)
        }
        exception<RequestValidationException> { call, cause ->
            val detail = cause.reasons.joinToString("; ").ifBlank { cause.message }
            respondProblem(call, PosErrorCode.VALIDATION_FAILED, HttpStatusCode.BadRequest, detail, cause)
        }
        exception<BadRequestException> { call, cause ->
            respondProblem(call, PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, cause.message, cause)
        }
        exception<IllegalArgumentException> { call, cause ->
            respondProblem(call, PosErrorCode.INVALID_REQUEST, HttpStatusCode.BadRequest, cause.message, cause)
        }
        exception<UnauthorizedException> { call, cause ->
            respondProblem(call, PosErrorCode.AUTH_FORBIDDEN, HttpStatusCode.Forbidden, cause.message, cause)
        }
        exception<Throwable> { call, cause ->
            respondProblem(
                call,
                PosErrorCode.INTERNAL_ERROR,
                HttpStatusCode.InternalServerError,
                "See server logs with traceId.",
                cause,
            )
        }
        // Status interceptors (no exception, just a 4xx from auth/permissions plugins)
        status(HttpStatusCode.Unauthorized) { call, _ ->
            respondProblem(call, PosErrorCode.AUTH_INVALID_CREDENTIALS, HttpStatusCode.Unauthorized, null, null)
        }
        status(HttpStatusCode.Forbidden) { call, _ ->
            respondProblem(call, PosErrorCode.AUTH_FORBIDDEN, HttpStatusCode.Forbidden, null, null)
        }
    }
}

private suspend fun respondProblem(
    call: ApplicationCall,
    code: PosErrorCode,
    status: HttpStatusCode,
    detail: String?,
    cause: Throwable?,
    fields: List<FieldError> = emptyList(),
) {
    // Put code + statusCode on MDC so the error.log appender (logback.xml) can
    // include them in the line prefix. Cleared in finally to avoid leaking
    // into the next request on the same thread. We use `statusCode` (not
    // `status`) to avoid colliding with the Logstash JSON encoder reserved
    // `status` field.
    MDC.put("code", code.name)
    MDC.put("statusCode", status.value.toString())
    try {
        logOutcome(call, code, status, detail, cause)

        val built = ProblemDetailFactory.build(code, status, detail, call.request.path(), fields)
        // A refusal raised before CallLogging fills the MDC (the terminal ceiling, the auth plugins)
        // would otherwise report an empty traceId; the call id IS that trace id.
        val pd = if (built.traceId.isNullOrBlank()) built.copy(traceId = call.callId ?: "") else built
        call.respondText(
            text = problemJson.encodeToString(ProblemDetail.serializer(), pd),
            contentType = PROBLEM_JSON,
            status = status,
        )
    } finally {
        MDC.remove("code")
        MDC.remove("statusCode")
    }
}

/**
 * A 4xx is an expected, client-correctable outcome, not an operator-visible failure: an expired
 * bearer is precisely what drives the client's refresh flow, and a missing HR record is the normal
 * "not captured yet" case the client already reads as null. Logging those at ERROR filled
 * logs/error.log with 34 expired-token 401s in one e2e run, where each reads as a fault, so a 4xx
 * never goes there (E2E-2026-09-15 N12).
 *
 * It used to go nowhere at all (DEBUG, off), which left "why was this refused?" unanswerable once
 * a user read a traceId out of the error dialog. It now goes to the dedicated `rejected` logger,
 * i.e. rejected.log, with its own retention. Only a 5xx — something the server itself got wrong —
 * belongs in error.log.
 */
private fun logOutcome(
    call: ApplicationCall,
    code: PosErrorCode,
    status: HttpStatusCode,
    detail: String?,
    cause: Throwable?,
) {
    if (status.value < 500) {
        logRejection(call, code, status, detail ?: cause?.message)
        return
    }
    if (cause != null) {
        logger
            .atError()
            .setCause(cause)
            .log("Request failed: ${cause.message ?: code.name}")
    } else {
        logger.error("Request failed: ${code.name} ${status.value}")
    }
}

/**
 * Writes one `rejected` line. `traceId`, `method` and `path` are put into the MDC here, only when
 * absent: a refusal raised before CallLogging fills the MDC (the route-authorization plugin runs in
 * `onCall`, the terminal ceiling and auth plugins likewise) would otherwise be logged with an empty
 * trace id — exactly the rejections people ask about. Nothing here suspends, so the thread-local MDC
 * cannot leak to another request between put and remove.
 *
 * The line carries the reason text as written; scrubbing happens when the maintainer reads it, never
 * at rest. `FieldError` values are deliberately not logged: they carry user input.
 */
private fun logRejection(
    call: ApplicationCall,
    code: PosErrorCode,
    status: HttpStatusCode,
    reason: String?,
) {
    val added = mutableListOf<String>()

    fun putIfAbsent(
        key: String,
        value: String?,
    ) {
        if (value != null && MDC.get(key).isNullOrBlank()) {
            MDC.put(key, value)
            added += key
        }
    }
    putIfAbsent(TRACE_ID_MDC_KEY, call.callId)
    putIfAbsent("method", call.request.httpMethod.value)
    putIfAbsent("path", call.request.path())
    try {
        rejectedLogger.info("{} {}{}", code.name, status.value, reason?.let { " — $it" }.orEmpty())
    } finally {
        added.forEach(MDC::remove)
    }
}
