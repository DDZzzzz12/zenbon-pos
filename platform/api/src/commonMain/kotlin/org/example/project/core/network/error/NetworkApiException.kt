package org.example.project.core.network.error

/**
 * Thrown by [HttpClient.apiCall] when the request never made it to the server
 * (transport / IO failure, connection refused, etc.).
 */
class NetworkApiException(
    cause: Throwable,
) : RuntimeException(cause.message, cause)
