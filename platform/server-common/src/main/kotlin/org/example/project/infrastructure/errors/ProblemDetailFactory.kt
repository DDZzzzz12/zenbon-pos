package org.example.project.infrastructure.errors

import io.ktor.http.HttpStatusCode
import org.example.project.core.error.FieldError
import org.example.project.core.error.PosErrorCode
import org.example.project.core.error.ProblemDetail
import org.slf4j.MDC

/**
 * Builds a ProblemDetail for a given PosException-equivalent error.
 * traceId is read from MDC (populated by Ktor CallId + CallLogging.callIdMdc).
 * Returns "" for traceId when MDC has no value (non-Ktor contexts) so the
 * field is always a String, not null — clients tolerate empty strings.
 */
object ProblemDetailFactory {
    private const val TYPE_BASE = "https://pos.example.com/errors/"

    fun build(
        code: PosErrorCode,
        status: HttpStatusCode,
        detail: String?,
        instance: String?,
        errors: List<FieldError> = emptyList(),
    ): ProblemDetail =
        ProblemDetail(
            type = TYPE_BASE + code.name.lowercase().replace('_', '-'),
            title = titleOf(code),
            status = status.value,
            code = code.name,
            detail = detail,
            instance = instance,
            traceId = MDC.get("traceId") ?: "",
            errors = errors.ifEmpty { null },
        )

    private fun titleOf(code: PosErrorCode): String =
        code.name.split('_').joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.uppercase() }
        }
}
