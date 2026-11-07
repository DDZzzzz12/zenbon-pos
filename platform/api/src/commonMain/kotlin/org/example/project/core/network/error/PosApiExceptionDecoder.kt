package org.example.project.core.network.error

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import org.example.project.core.error.PosErrorCode
import org.example.project.core.error.ProblemDetail

/**
 * Decodes a non-2xx [HttpResponse] into a [PosApiException]. When the body is
 * application/problem+json, fields map 1:1; for other shapes (legacy
 * mapOf("error" to "..."), plain text, empty body), falls back to
 * [PosErrorCode.INTERNAL_ERROR] with the body excerpt as the message.
 *
 * Never throws — graceful fallback is the contract.
 */
object PosApiExceptionDecoder {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private const val TRACE_ID_HEADER = "X-Request-ID"

    suspend fun decode(resp: HttpResponse): PosApiException {
        val body = runCatching { resp.bodyAsText() }.getOrDefault("")
        val headerTraceId = resp.headers[TRACE_ID_HEADER]
        val problem = parseProblemDetail(body)
        if (problem != null) {
            val code = parseCode(problem.code) ?: PosErrorCode.INTERNAL_ERROR
            return PosApiException(
                code = code,
                httpStatus = problem.status ?: resp.status.value,
                message = problem.detail ?: problem.title,
                traceId = problem.traceId ?: headerTraceId,
                fieldErrors = problem.errors.orEmpty(),
            )
        }
        return PosApiException(
            code = PosErrorCode.INTERNAL_ERROR,
            httpStatus = resp.status.value,
            message = body.take(500).ifBlank { null },
            traceId = headerTraceId,
        )
    }

    private fun parseProblemDetail(body: String): ProblemDetail? {
        if (body.isBlank()) return null
        return runCatching { json.decodeFromString(ProblemDetail.serializer(), body) }.getOrNull()
    }

    private fun parseCode(name: String?): PosErrorCode? {
        if (name.isNullOrBlank()) return null
        return runCatching { PosErrorCode.valueOf(name) }.getOrNull()
    }
}
