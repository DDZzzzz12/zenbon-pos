package org.example.project.infrastructure.plugins

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLoggingConfig
import java.util.UUID

val TRACE_ID_HEADER: String = HttpHeaders.XRequestId

/** Matches `transaction_audit_log.trace_id`, which is `varchar(64)`. See [configureCallId]. */
const val MAX_TRACE_ID_LENGTH: Int = 64

/**
 * Installs Ktor's CallId plugin. Reads X-Request-ID from the incoming request,
 * generates a UUID when absent or empty, and echoes the chosen value back as
 * X-Request-ID on the response.
 *
 * Must be installed before [configureCallLogging] so CallLogging.callIdMdc
 * picks the same value.
 */
fun Application.configureCallId() {
    install(CallId) {
        // `retrieveFromHeader`, NOT `header`: `header(name)` is retrieveFromHeader + replyToHeader, so
        // combined with the `replyToHeader` below it registered two replies and every response
        // carried X-Request-ID twice. `replyToHeader` is the single source of the response header.
        retrieveFromHeader(TRACE_ID_HEADER)
        generate { UUID.randomUUID().toString() }
        // The length bound is COUPLED to the database: `transaction_audit_log.trace_id` is
        // varchar(64), and every audit write sits inside a swallow-and-log catch. Without this, a
        // client-supplied X-Request-ID of 65+ characters was accepted, echoed and published to MDC,
        // then failed the INSERT — losing the audit row with only a log line to show for it. In
        // `cancelEntry` it was worse: that insert runs inside the ambient dbQuery, so aborting it
        // leaves the surrounding transaction failed after the catch has already swallowed the cause.
        //
        // Rejecting here rather than truncating at the capture sites is deliberate: an oversized
        // header falls back to the generated UUID below, which is then echoed, logged AND stored
        // consistently. A `take(64)` at the capture site would leave the header, the log line and
        // the row disagreeing — which is exactly what the column exists to prevent.
        verify { id -> id.isNotBlank() && id.length <= MAX_TRACE_ID_LENGTH }
        replyToHeader(TRACE_ID_HEADER)
    }
}

/**
 * Adds traceId to the CallLogging MDC. Must be called from inside the
 * existing CallLogging install block — see CallLogging.kt.
 */
internal fun CallLoggingConfig.installTraceIdMdc() {
    callIdMdc(TRACE_ID_MDC_KEY)
}
