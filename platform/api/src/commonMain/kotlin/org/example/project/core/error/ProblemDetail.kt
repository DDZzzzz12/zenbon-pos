package org.example.project.core.error

import kotlinx.serialization.Serializable

/**
 * RFC 7807 ProblemDetail wire envelope. Shared by client + server: the server
 * encodes this as `application/problem+json`; the client decodes it in
 * `PosApiExceptionDecoder`. Optional fields are nullable to tolerate older
 * responses and partial payloads.
 */
@Serializable
data class ProblemDetail(
    val type: String? = null,
    val title: String? = null,
    val status: Int? = null,
    val code: String? = null,
    val detail: String? = null,
    val instance: String? = null,
    val traceId: String? = null,
    val errors: List<FieldError>? = null,
)
