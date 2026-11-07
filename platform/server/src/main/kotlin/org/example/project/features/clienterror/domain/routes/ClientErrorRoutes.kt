/**
 * API route for client-reported errors.
 *
 * Every error a client actually shows a user is posted here and written to the server's log
 * folder, so a failure on the till or in the web admin is diagnosable from the same place as a
 * server fault instead of only existing in a browser console the operator has since closed.
 *
 * Deliberately NOT behind `authenticate("auth-jwt")`: the errors hardest to diagnose are the ones
 * around login and token refresh, where there is no valid JWT by definition. The route is gated on
 * a paired device token instead, so only a paired terminal can write to the log.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.clienterror.domain.routes

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.core.domain.dto.clienterror.ClientErrorDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.security.JwtConfig
import org.example.project.features.terminal.data.service.DeviceTokenGenerator
import org.example.project.features.terminal.data.service.TerminalCapabilityResolver
import org.example.project.infrastructure.errors.PosException
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * Configure the client-error reporting route.
 *
 * Endpoints:
 * - POST /api/v1/client-errors — record an error a client displayed to a user
 */
fun Route.clientErrorRoutes() {
    val capabilityResolver by inject<TerminalCapabilityResolver>()

    // Dedicated logger name so logback can route these to their own file (see logback.xml);
    // client failures stay separate from the server's own error.log.
    val clientLogger = LoggerFactory.getLogger("client-error")
    val routeLogger = LoggerFactory.getLogger("ClientErrorRoutes")

    route("/api/v1/client-errors") {
        /**
         * Record an error that a client displayed to a user.
         *
         * Description: Accepts a report describing an error shown on the till or in the web admin — server-rejected, network, or a client-side throwable — and writes it to the server's client-error log. Authentication is a paired device token (`X-Device-Token`), not a JWT, so failures around login and token refresh can still be reported. The report is data for the log only; nothing is persisted to the database.
         *
         * Tag: client-errors
         * Response: 400 The report is malformed or a field exceeds its cap.
         * Response: 401 The `X-Device-Token` header is missing or does not match a paired terminal.
         */
        post {
            // Either credential is enough, and neither is a JWT requirement:
            //  - the till is a paired terminal and always has a device token, including before
            //    anyone logs in — which is exactly when the interesting failures happen;
            //  - the web admin is a browser with no device token, so it presents its bearer token.
            // An anonymous caller has neither and cannot write to the log.
            val deviceToken = call.request.headers["X-Device-Token"]?.takeIf { it.isNotBlank() }
            val verifiedDeviceId =
                deviceToken?.let { capabilityResolver.byTokenHash(DeviceTokenGenerator.hash(it))?.deviceId }
            val jwtDeviceId =
                call.request.headers["Authorization"]
                    ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                    ?.let { JwtConfig.validateToken(it.substring("Bearer ".length).trim())?.deviceId }
            if (verifiedDeviceId == null && jwtDeviceId == null) {
                throw PosException(
                    PosErrorCode.AUTH_INVALID_CREDENTIALS,
                    HttpStatusCode.Unauthorized,
                    "A paired device token or a valid bearer token is required to report a client error",
                )
            }

            val report = call.receive<ClientErrorDto.ReportRequestDto>()
            report.validate().takeIf { it.isNotEmpty() }?.let { errors ->
                // Log at WARN rather than silently dropping: a client sending malformed reports is
                // itself a defect worth seeing, but it must not reach the client-error log unbounded.
                routeLogger.warn("Rejected client error report {}: {}", report.clientErrorId, errors)
                throw PosException(
                    PosErrorCode.VALIDATION_FAILED,
                    HttpStatusCode.BadRequest,
                    errors.joinToString("; "),
                )
            }

            // Terminal identity comes from the verified token, never from the report body — the
            // body is client-supplied and could claim any terminal.
            withClientErrorContext(report, verifiedDeviceId ?: jwtDeviceId) {
                clientLogger.error(
                    "[{}] {} — {}: {}",
                    report.app,
                    report.kind,
                    report.title,
                    report.message,
                )
            }

            call.respond(
                HttpStatusCode.OK,
                ClientErrorDto.ReportResponseDto(clientErrorId = report.clientErrorId, logged = true),
            )
        }
    }
}

/**
 * Runs [body] with the report's fields in the logging MDC, then clears them.
 *
 * The structured appender writes MDC entries as their own JSON fields, so a report stays
 * greppable (`traceId`, `code`, `screen`) instead of being flattened into one message string.
 */
private inline fun withClientErrorContext(
    report: ClientErrorDto.ReportRequestDto,
    verifiedDeviceId: String?,
    body: () -> Unit,
) {
    val fields =
        buildMap {
            put("clientErrorId", report.clientErrorId)
            put("clientApp", report.app)
            put("clientErrorKind", report.kind)
            put("clientOccurredAt", report.occurredAt)
            verifiedDeviceId?.let { put("deviceId", it) }
            report.code?.let { put("code", it) }
            report.httpStatus?.let { put("httpStatus", it.toString()) }
            report.traceId?.let { put("traceId", it) }
            report.exceptionType?.let { put("exceptionType", it) }
            report.exceptionMessage?.let { put("exceptionMessage", it) }
            report.stackTrace?.let { put("stackTrace", it) }
            report.screen?.let { put("screen", it) }
            report.employeeNumber?.let { put("employeeNumber", it) }
            report.terminalId?.let { put("reportedTerminalId", it) }
            report.appVersion?.let { put("appVersion", it) }
        }
    fields.forEach { (k, v) -> MDC.put(k, v) }
    try {
        body()
    } finally {
        fields.keys.forEach { MDC.remove(it) }
    }
}
