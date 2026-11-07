package org.example.project.core.network.error

import org.example.project.core.error.FieldError
import org.example.project.core.error.PosErrorCode

/**
 * Thrown by [HttpClient.apiCall] when the server returns a non-2xx response
 * (decoded from application/problem+json when present, falls back to a
 * minimal PosApiException for other shapes).
 */
class PosApiException(
    val code: PosErrorCode,
    val httpStatus: Int,
    override val message: String?,
    val traceId: String?,
    val fieldErrors: List<FieldError> = emptyList(),
) : RuntimeException(message)
