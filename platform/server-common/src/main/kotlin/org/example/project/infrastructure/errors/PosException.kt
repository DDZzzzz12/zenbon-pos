package org.example.project.infrastructure.errors

import io.ktor.http.HttpStatusCode
import org.example.project.core.error.FieldError
import org.example.project.core.error.PosErrorCode

/**
 * Throw to surface a structured error. Caught by the global StatusPages
 * handler in pos-server, which converts to application/problem+json under
 * the current MDC traceId. A 5xx is logged to logs/error.log; a 4xx is a
 * client-correctable rejection and is logged at DEBUG only.
 *
 * `open` so a feature can name one specific failure as a subtype while keeping this class's code
 * and status -- StatusPages resolves handlers up the class hierarchy, so a subclass still renders
 * as the same problem+json, and every `catch (e: PosException)` still catches it. Subclass only to
 * let a caller branch on the cause; never to change how the error is reported.
 */
open class PosException(
    val code: PosErrorCode,
    val httpStatus: HttpStatusCode,
    message: String? = null,
    val fields: List<FieldError> = emptyList(),
    cause: Throwable? = null,
) : RuntimeException(message, cause)
